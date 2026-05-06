package io.aeyer.voidcore.ws;

import io.aeyer.voidcore.ws.flow.ScreenRouter;
import io.aeyer.voidcore.ws.protocol.ServerMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.SubProtocolCapable;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.List;

/**
 * Transport-layer WebSocket handler. One instance, one thread per connection
 * (virtual threads — see ADR-002). Per SPEC §4.1, §4.2.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Advertise the {@code voidcore-node-v1} subprotocol on the upgrade.</li>
 *   <li>Track connections in {@link SessionRegistry}.</li>
 *   <li>Parse the {@link Envelope} and reject mismatched protocol versions
 *       with {@code PROTOCOL_VERSION_MISMATCH} (SPEC §4.7).</li>
 *   <li>Reset the heartbeat counter on every WebSocket-protocol pong frame.</li>
 *   <li>Hand validated envelopes to {@link MessageDispatcher} (#14 plugs the
 *       sealed-types router in here).</li>
 * </ul>
 *
 * <p>{@code seq} and {@code mac} envelope fields are accepted and ignored on
 * v1, reserved for v2 forward compatibility (ADR-018).
 */
@Component
public class BbsWebSocketHandler extends TextWebSocketHandler implements SubProtocolCapable {

    public static final String PROTOCOL_VERSION = "voidcore-node-v1";
    public static final int MAX_TEXT_MESSAGE_BYTES = 64 * 1024;

    private static final Logger log = LoggerFactory.getLogger(BbsWebSocketHandler.class);

    private final SessionRegistry registry;
    private final MessageDispatcher dispatcher;
    private final ObjectMapper json;
    private final ScreenRouter screen;

    public BbsWebSocketHandler(SessionRegistry registry,
                               MessageDispatcher dispatcher,
                               ObjectMapper json,
                               ObjectProvider<ScreenRouter> screenProvider) {
        this.registry = registry;
        this.dispatcher = dispatcher;
        this.json = json;
        this.screen = screenProvider.getIfAvailable();
    }

    @Override
    public List<String> getSubProtocols() {
        return List.of(PROTOCOL_VERSION);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.debug("[ws-trace] connect-begin id={} remote={} subprotocol={}",
                session.getId(), session.getRemoteAddress(), session.getAcceptedProtocol());
        session.setTextMessageSizeLimit(MAX_TEXT_MESSAGE_BYTES);
        VoidCoreSession s = registry.register(session);
        log.debug("[ws-trace] connect-registered id={} registry-size={}",
                session.getId(), registry.size());
        if (screen != null) {
            log.debug("[ws-trace] connect-router-onConnect id={}", session.getId());
            screen.onConnect(s);
        }
        log.debug("[ws-trace] connect-end id={}", session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.debug("[ws-trace] disconnect-begin wsId={} code={} reason={}",
                session.getId(), status.getCode(), status.getReason());
        // Detach (NOT remove): for post-auth sessions the actor is moved
        // to the detached pool with a TTL armed, so a quick reconnect
        // can resume in place. For anon sessions the actor is terminated
        // immediately. Per-session cleanup (phases / nav / bus / presence)
        // runs on terminate via the registry's termination listener
        // wired by ScreenRouter — not on every detach. See ADR-033.
        SessionRegistry.DetachOutcome outcome = registry.detach(session.getId());
        log.debug("[ws-trace] disconnect-end wsId={} outcome={} active={} detached={}",
                session.getId(), outcome, registry.size(), registry.detachedSize());
    }

    @Override
    protected void handleTextMessage(WebSocketSession underlying, TextMessage message) {
        String payload = message.getPayload();
        log.debug("[ws-trace] inbound-raw id={} bytes={} payload-head={}",
                underlying.getId(), message.getPayloadLength(),
                payload.length() > 80 ? payload.substring(0, 80) + "..." : payload);

        VoidCoreSession session = registry.get(underlying.getId());
        if (session == null) {
            // The actor is gone — either the WS arrived after detach (race),
            // or the registry's TTL sweeper terminated the session while the
            // WS happened to still be alive. Either way the client has no
            // way to know unless we tell them. Send resume.err and close so
            // the client's session-expired handler can reload to login.
            log.info("[ws-trace] inbound-no-actor wsId={} type-head={} — sending resume.err",
                    underlying.getId(),
                    payload.length() > 80 ? payload.substring(0, 80) : payload);
            try {
                ServerMessage.ResumeErr err = new ServerMessage.ResumeErr(
                        "AUTH_REQUIRED", "session expired");
                // Build the wire envelope manually since there's no VoidCoreSession
                // proxy to call .send() on.
                com.fasterxml.jackson.databind.node.ObjectNode env =
                        json.createObjectNode();
                env.putNull("id");
                env.put("seq", 0);
                env.putNull("mac");
                env.put("type", "resume.err");
                env.set("payload", json.valueToTree(err));
                underlying.sendMessage(new TextMessage(env.toString()));
            } catch (Exception e) {
                log.debug("[ws-trace] inbound-no-actor-send-fail wsId={} ex={}",
                        underlying.getId(), e.toString());
            }
            try {
                underlying.close(CloseStatus.NORMAL.withReason("session expired"));
            } catch (Exception e) {
                log.debug("[ws-trace] inbound-no-actor-close-fail wsId={} ex={}",
                        underlying.getId(), e.toString());
            }
            return;
        }
        log.debug("[ws-trace] inbound-session-found id={} userId={} handle={}",
                underlying.getId(), session.userId(), session.handle());

        // Active text frames count as liveness too — reset the missed-pong
        // counter so a transient pong-flow blip doesn't reap an actively-
        // used connection. The HeartbeatScheduler still drives the canonical
        // liveness check via pings/pongs; this is defense in depth.
        session.resetPingsSinceLastPong();

        Envelope inbound;
        try {
            inbound = json.readValue(payload, Envelope.class);
        } catch (Exception e) {
            log.warn("[ws-trace] inbound-parse-fail id={} err={}", session.id(), e.toString());
            session.sendError("VALIDATION", "malformed envelope");
            return;
        }
        log.debug("[ws-trace] inbound-parsed id={} envelope-id={} type={} pv={}",
                session.id(), inbound.id(), inbound.type(), inbound.protocol_version());

        if (inbound.type() == null || inbound.type().isBlank()) {
            log.warn("[ws-trace] inbound-type-missing id={}", session.id());
            session.sendError("VALIDATION", "envelope.type is required");
            return;
        }

        if (!PROTOCOL_VERSION.equals(inbound.protocol_version())) {
            log.warn("[ws-trace] inbound-protocol-mismatch id={} client-pv={} expected={}",
                    session.id(), inbound.protocol_version(), PROTOCOL_VERSION);
            session.sendError("PROTOCOL_VERSION_MISMATCH",
                    "expected protocol_version=" + PROTOCOL_VERSION
                            + ", got " + inbound.protocol_version());
            session.close(CloseStatus.PROTOCOL_ERROR.withReason("protocol version mismatch"));
            return;
        }

        // Diagnostic INFO log so the WS-arrival boundary is visible without
        // touching log levels — paired with ScreenRouter's keystroke log
        // below to triangulate where a "nothing fires" report lives. Cheap
        // (one log per inbound message) and worth keeping for a few releases.
        log.info("ws session={} inbound type={}", session.id(), inbound.type());
        // seq and mac are reserved for v2 (ADR-018). Accept and ignore on v1.
        log.debug("[ws-trace] inbound-dispatch id={} type={}", session.id(), inbound.type());
        dispatcher.dispatch(inbound, session);
        log.debug("[ws-trace] inbound-dispatch-done id={} type={}", session.id(), inbound.type());
    }

    @Override
    protected void handlePongMessage(WebSocketSession underlying, PongMessage message) {
        // Diagnostic: log every pong received. If sessions are being closed
        // for "missed pongs" but THIS log is silent, the browser isn't
        // ponging at all (or Tomcat's frame demuxer isn't routing pongs
        // here). If THIS log fires but the missed-pong counter still goes
        // up, the resetPingCounter logic is broken.
        log.info("ws pong received underlyingId={} session-known={}",
                underlying.getId(), registry.get(underlying.getId()) != null);
        VoidCoreSession session = registry.get(underlying.getId());
        int prevCounter = session == null ? -1 : session.pingsSinceLastPong();
        log.debug("[ws-trace] heartbeat-pong id={} session-known={} counter-was={}",
                underlying.getId(), session != null, prevCounter);
        if (session != null) session.resetPingCounter();
    }

    @Override
    public void handleTransportError(WebSocketSession underlying, Throwable exception) {
        log.warn("[ws-trace] transport-error id={} ex={}",
                underlying.getId(), exception.toString());
    }
}
