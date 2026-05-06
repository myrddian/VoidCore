package io.aeyer.voidcore.ws;

import io.aeyer.voidcore.ws.flow.ScreenRouter;
import io.aeyer.voidcore.ws.flow.ui.AppEvent;
import io.aeyer.voidcore.ws.protocol.ClientMessage;
import io.aeyer.voidcore.ws.protocol.ProtocolTypeRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Routes inbound envelopes to typed message handlers via switch
 * pattern matching on a {@link ClientMessage} variant. The switch is
 * exhaustive over the sealed type, so adding a new variant breaks the build
 * here until a case is added — that compile-time totality is the whole point
 * of the sealed-types treatment (DECISIONS.md ADR-001).
 *
 * <p>This is the single dispatcher: business handlers (auth, presence, chat,
 * etc.) get plugged in by replacing each branch as the relevant ticket lands.
 * Until then every recognised, validated variant emits a clear {@code error}
 * with code {@code NOT_IMPLEMENTED} so the wire is observable end-to-end.
 *
 * <p>Failure modes (all observable):
 * <ul>
 *   <li>Unknown {@code type}: {@code VALIDATION} with the offending name.</li>
 *   <li>Payload shape mismatch: {@code VALIDATION} with the deserialiser error.</li>
 *   <li>Constraint violation: {@code VALIDATION} with field path + reason.</li>
 *   <li>Recognised + valid: {@code NOT_IMPLEMENTED} (until handler lands).</li>
 * </ul>
 */
@Component
public class RoutingMessageDispatcher implements MessageDispatcher {

    private static final Logger log = LoggerFactory.getLogger(RoutingMessageDispatcher.class);

    private final ObjectMapper json;
    private final ProtocolTypeRegistry registry;
    private final Validator validator;
    private final ScreenRouter screen;

    public RoutingMessageDispatcher(ObjectMapper json,
                                    ProtocolTypeRegistry registry,
                                    Validator validator,
                                    ObjectProvider<ScreenRouter> screenProvider) {
        this.json = json;
        this.registry = registry;
        this.validator = validator;
        // ScreenRouter is gated on DataSource (AuthConfig). Test contexts
        // that exclude DB autoconfigs (smoke test, WS transport test) don't
        // wire it; we fall back to the same NOT_IMPLEMENTED response that
        // the pre-wired #14 dispatcher emitted there.
        this.screen = screenProvider.getIfAvailable();
    }

    @Override
    public void dispatch(Envelope inbound, VoidCoreSession session) {
        log.debug("[ws-trace] dispatch-begin id={} type={}", session.id(), inbound.type());
        Class<? extends ClientMessage> cls = registry.clientClassFor(inbound.type()).orElse(null);
        if (cls == null) {
            log.warn("[ws-trace] dispatch-unknown-type id={} type={}", session.id(), inbound.type());
            session.sendError("VALIDATION", "unknown type: " + inbound.type());
            return;
        }

        ClientMessage typed;
        try {
            log.info("inbound payload={}", inbound.payload().toString());
            typed = json.treeToValue(inbound.payload(), cls);
        } catch (Exception e) {
            log.warn("[ws-trace] dispatch-payload-decode-fail id={} type={} err={}",
                    session.id(), inbound.type(), e.toString());
            session.sendError("VALIDATION", "malformed payload for " + inbound.type());
            return;
        }

        Set<ConstraintViolation<ClientMessage>> violations = validator.validate(typed);
        if (!violations.isEmpty()) {
            ConstraintViolation<ClientMessage> first = violations.iterator().next();
            String fieldPath = first.getPropertyPath().toString();
            log.warn("[ws-trace] dispatch-validation-fail id={} type={} field={} msg={}",
                    session.id(), inbound.type(), fieldPath, first.getMessage());
            session.sendError("VALIDATION",
                    fieldPath + ": " + first.getMessage());
            return;
        }
        log.debug("[ws-trace] dispatch-typed id={} typed-class={}",
                session.id(), typed.getClass().getSimpleName());

        // Switch is exhaustive over the sealed type; missing branches fail
        // compilation. ScreenRouter handles the user-facing flow when
        // DataSource (and therefore AuthService) is wired; otherwise we
        // fall back to NOT_IMPLEMENTED so the wire is still observable in
        // DB-less test contexts.
        if (screen == null) {
            switch (typed) {
                case ClientMessage.AuthLogin    m -> notImplemented(session, m);
                case ClientMessage.AuthRegister m -> notImplemented(session, m);
                case ClientMessage.AuthResume   m -> notImplemented(session, m);
                case ClientMessage.AuthLogout   m -> notImplemented(session, m);
                case ClientMessage.Keystroke    m -> notImplemented(session, m);
                case ClientMessage.LineSubmit   m -> notImplemented(session, m);
                case ClientMessage.LineCancel   m -> notImplemented(session, m);
                case ClientMessage.ScrollRequest m -> notImplemented(session, m);
                case ClientMessage.ViewportResize m -> notImplemented(session, m);
                case ClientMessage.EditorCommit m -> notImplemented(session, m);
                case ClientMessage.EditorCancel m -> notImplemented(session, m);
                case ClientMessage.EditorSnapshot m -> notImplemented(session, m);
                case ClientMessage.FieldCommit m -> notImplemented(session, m);
                case ClientMessage.FieldCancel m -> notImplemented(session, m);
                case ClientMessage.FocusMove m -> notImplemented(session, m);
            }
            return;
        }
        log.debug("[ws-trace] dispatch-route id={} → ScreenRouter.{}",
                session.id(), routerMethodFor(typed));
        // Pass the inbound envelope id through so response messages
        // (auth.ok, auth.err, resume.ok, resume.err) can echo it for
        // request/response correlation per SPEC §4.
        String inboundId = inbound.id();
        switch (typed) {
            case ClientMessage.AuthLogin    m -> screen.onAuthLogin(session, m, inboundId);
            case ClientMessage.AuthRegister m -> screen.onAuthRegister(session, m, inboundId);
            case ClientMessage.AuthResume   m -> screen.onAuthResume(session, m, inboundId);
            case ClientMessage.AuthLogout   m -> screen.onAuthLogout(session);
            case ClientMessage.Keystroke    m -> screen.onKeystroke(session, m);
            case ClientMessage.LineSubmit   m -> screen.onLineSubmit(session, m);
            case ClientMessage.LineCancel   m -> screen.onLineCancel(session);
            case ClientMessage.ScrollRequest m -> notImplemented(session, m);
            case ClientMessage.ViewportResize m -> notImplemented(session, m);
            case ClientMessage.EditorCommit m -> screen.onAppEvent(session,
                    new AppEvent.EditorCommit(m.widget_id(), m.content(), m.action()));
            case ClientMessage.EditorCancel m -> screen.onAppEvent(session,
                    new AppEvent.EditorCancel(m.widget_id(), m.force()));
            case ClientMessage.EditorSnapshot m -> screen.onAppEvent(session,
                    new AppEvent.EditorSnapshot(m.widget_id(), m.content()));
            case ClientMessage.FieldCommit m -> screen.onAppEvent(session,
                    new AppEvent.FieldCommit(m.widget_id(), m.value()));
            case ClientMessage.FieldCancel m -> screen.onAppEvent(session,
                    new AppEvent.FieldCancel(m.widget_id()));
            case ClientMessage.FocusMove m -> screen.onAppEvent(session,
                    new AppEvent.FocusMove(m.from(), m.direction()));
        }
        log.debug("[ws-trace] dispatch-end id={} type={}", session.id(), inbound.type());
    }

    private static String routerMethodFor(ClientMessage m) {
        return switch (m) {
            case ClientMessage.AuthLogin x      -> "onAuthLogin";
            case ClientMessage.AuthRegister x   -> "onAuthRegister";
            case ClientMessage.AuthResume x     -> "onAuthResume";
            case ClientMessage.AuthLogout x     -> "onAuthLogout";
            case ClientMessage.Keystroke x      -> "onKeystroke(key=" + x.key() + ")";
            case ClientMessage.LineSubmit x     -> "onLineSubmit(text-len=" + (x.text() == null ? 0 : x.text().length()) + ")";
            case ClientMessage.LineCancel x     -> "onLineCancel";
            case ClientMessage.ScrollRequest x  -> "scrollRequest [not impl]";
            case ClientMessage.ViewportResize x -> "viewportResize [not impl]";
            case ClientMessage.EditorCommit x   -> "onAppEvent(EditorCommit widget=" + x.widget_id() + ")";
            case ClientMessage.EditorCancel x   -> "onAppEvent(EditorCancel widget=" + x.widget_id() + ")";
            case ClientMessage.EditorSnapshot x -> "onAppEvent(EditorSnapshot widget=" + x.widget_id() + ")";
            case ClientMessage.FieldCommit x    -> "onAppEvent(FieldCommit widget=" + x.widget_id() + ")";
            case ClientMessage.FieldCancel x    -> "onAppEvent(FieldCancel widget=" + x.widget_id() + ")";
            case ClientMessage.FocusMove x      -> "onAppEvent(FocusMove from=" + x.from() + " dir=" + x.direction() + ")";
        };
    }

    private void notImplemented(VoidCoreSession session, ClientMessage m) {
        String typeName = m.getClass().getSimpleName();
        log.warn("session={} accepted but not yet handled: {}", session.id(), typeName);
        session.sendError("NOT_IMPLEMENTED", typeName + " handler not wired yet");
    }
}
