package io.aeyer.voidcore.ws;

import io.aeyer.voidcore.ws.protocol.ServerMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Duration;

/**
 * Sends WebSocket protocol-level ping frames every {@code voidcore.ws.heartbeat-seconds}
 * (default 30s) per SPEC §4.1. Browsers auto-respond with pong; on each pong
 * arrival {@link BbsWebSocketHandler#handlePongMessage} resets the per-session
 * counter. After {@code voidcore.ws.max-missed-pongs} consecutive missed responses
 * (default 3), the session is closed with GOING_AWAY.
 *
 * <p>Synchronisation: Spring's {@code WebSocketSession} is not safe for
 * concurrent writes. The scheduler iterates {@link SessionRegistry#all()}
 * which returns {@code SessionProxy} instances; every call here
 * ({@link VoidCoreSession#isOpen()}, {@link VoidCoreSession#sendPing(ByteBuffer)},
 * {@link VoidCoreSession#close(CloseStatus)},
 * {@link VoidCoreSession#incrementPingsSinceLastPong()}) routes through the
 * per-session actor's queue. That serialises ping writes against any
 * concurrent text-frame send on the same session — Tomcat's underlying
 * endpoint never sees two writers. Calls across sessions are independent
 * (different actors / different threads) and run in parallel; sessions
 * within this scheduler tick are visited sequentially, which is fine at
 * board scale (few hundred sessions, sub-millisecond enqueue each).
 */
@Component
public class HeartbeatScheduler {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatScheduler.class);
    private static final ByteBuffer EMPTY = ByteBuffer.allocate(0);

    private final SessionRegistry registry;
    private final int maxMissed;

    public HeartbeatScheduler(SessionRegistry registry,
                              @Value("${voidcore.ws.max-missed-pongs:5}") int maxMissed) {
        this.registry = registry;
        this.maxMissed = maxMissed;
    }

    @Scheduled(fixedDelayString = "${voidcore.ws.heartbeat-seconds:30}", timeUnit = java.util.concurrent.TimeUnit.SECONDS)
    public void tick() {
        int total = registry.size();
        log.debug("[ws-trace] heartbeat-tick sessions={} maxMissed={}", total, maxMissed);
        for (VoidCoreSession session : registry.all()) {
            if (!session.isOpen()) {
                log.debug("[ws-trace] heartbeat-skip-closed id={}", session.id());
                continue;
            }
            int missed = session.incrementPingsSinceLastPong();
            if (missed > maxMissed) {
                // Hysteresis: re-check the counter after a brief pause.
                // With the actor model, pong delivery and the heartbeat
                // tick are FIFO-serialised through the same per-session
                // queue, so the pre-actor pong-vs-close race is gone in
                // theory. But this 50ms recheck is cheap belt-and-
                // suspenders against future regressions — and it has
                // visibly saved sessions during reconnect storms in the
                // pre-actor world.
                try { Thread.sleep(50); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                int recheck = session.pingsSinceLastPong();
                if (recheck > maxMissed) {
                    log.warn("[ws-trace] heartbeat-close id={} missed={} maxMissed={} recheck={}",
                            session.id(), missed - 1, maxMissed, recheck);
                    // Closes the WS only. Per ADR-033 the actor lives on
                    // in the detached pool until the TTL fires (or the
                    // client reconnects with the same token, in which
                    // case the actor is yanked back to ATTACHED with
                    // all in-flight state intact).
                    session.close(CloseStatus.GOING_AWAY.withReason("missed heartbeats"));
                    continue;
                }
                log.info("[ws-trace] heartbeat-grace-recovered id={} missed={} recheck={} (pong arrived during close window)",
                        session.id(), missed, recheck);
                // Treat as a normal tick — fall through to send the next ping.
            }
            log.debug("[ws-trace] heartbeat-ping id={} counter={}", session.id(), missed);
            sendPing(session, session.id());
            // ALSO send a system.heartbeat TEXT frame. Browser WebSocket
            // API hides protocol-level pings/pongs from JS, so the
            // frontend's `lastFrameAt` watchdog can only see text frames.
            // Without this, an idle-but-active connection (heartbeats
            // flowing fine at the protocol layer) trips the JS watchdog
            // after WATCHDOG_STALE_MS and the client force-closes with
            // code=1000 after ~90s of no UI-driving frames. The server-
            // side actor queue handles this cheaply; one tiny text frame
            // per session per heartbeat tick is well below board-scale
            // load.
            sendHeartbeat(session);
        }
        log.debug("[ws-trace] heartbeat-tick-end sessions-after={}", registry.size());
    }

    /**
     * Send a {@code system.heartbeat} text frame. The browser
     * WebSocket API doesn't expose protocol-level ping/pong to JS,
     * so the frontend's staleness watchdog can only see text frames.
     * One tiny heartbeat text per tick keeps the watchdog from
     * force-closing an idle-but-healthy connection.
     */
    private void sendHeartbeat(VoidCoreSession session) {
        try {
            session.send(new ServerMessage.SystemHeartbeat());
        } catch (IOException e) {
            log.warn("session={} heartbeat send failed: {}", session.id(), e.toString());
        }
        log.debug("[ws-trace] heartbeat-tick-end sessions-after={}", registry.size());
    }

    /**
     * Sends the ping through {@link VoidCoreSession#sendPing}, which on
     * {@link io.aeyer.voidcore.ws.session.SessionProxy} routes through the
     * session's actor queue. The actor serialises this with any text
     * frame send that happens to be in flight on the same session, so
     * Jakarta WebSocket {@code RemoteEndpoint.Basic} (which is not safe
     * for concurrent writers) only ever sees one write at a time.
     * Previously called {@code underlying.sendMessage} directly from
     * the scheduler thread which could race with a tomcat-handler
     * thread's text-frame send and tangle Tomcat's write queue.
     */
    private void sendPing(VoidCoreSession session, String sessionId) {
        try {
            session.sendPing(EMPTY.duplicate());
            log.debug("[ws-trace] heartbeat-ping-sent id={}", sessionId);
        } catch (IOException e) {
            log.warn("[ws-trace] heartbeat-ping-fail id={} ex={}", sessionId, e.toString());
        } catch (IllegalStateException e) {
            // Genuinely benign close-race between isOpen() and sendMessage();
            // DEBUG is right for this one.
            log.debug("[ws-trace] heartbeat-ping-race id={} ex={}", sessionId, e.toString());
        }
    }

    /** Visible for diagnostics / tests. */
    public Duration heartbeatInterval(@Value("${voidcore.ws.heartbeat-seconds:10}") int seconds) {
        return Duration.ofSeconds(seconds);
    }
}
