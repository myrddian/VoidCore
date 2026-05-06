package io.aeyer.voidcore.ws.flow.screen;

import io.aeyer.voidcore.auth.UserRepository.UserRow;
import io.aeyer.voidcore.ws.VoidCoreSession;
import io.aeyer.voidcore.ws.protocol.ServerMessage;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Per-call context passed to every {@link Screen} handler. Carries the
 * session, the authenticated user (or null pre-auth), and references to
 * the {@link Navigator} and {@link BbsServices} the screen needs.
 *
 * <p>Was a {@code record} earlier in the v1.4 refactor — switched to a
 * class because BbsContext has imperative behaviour ({@code send},
 * {@code push}, {@code pop}, {@code publish}, etc.) and isn't a value
 * type. Records carry value semantics (equals / hashCode / immutability
 * implications); BbsContext is a per-call binding of session-to-services
 * with helpers, not a value to compare or hash.
 *
 * <p>The {@code router} reference is also retained as
 * {@link #legacyRouter()} for transitional access to ScreenRouter
 * methods that haven't been moved to {@link BbsServices} yet — the
 * remaining {@code legacy*} bridges. Once all helpers have migrated
 * to {@code BbsServices} or the {@code MessageBus}, the router
 * back-reference goes away.
 */
public final class BbsContext {

    private static final Logger log = LoggerFactory.getLogger(BbsContext.class);

    private final VoidCoreSession session;
    private final UserRow user;
    private final Navigator navigator;
    private final BbsServices services;
    private final Object legacyRouter;

    public BbsContext(VoidCoreSession session,
                      UserRow user,
                      Navigator navigator,
                      BbsServices services,
                      Object legacyRouter) {
        this.session = session;
        this.user = user;
        this.navigator = navigator;
        this.services = services;
        this.legacyRouter = legacyRouter;
    }

    public VoidCoreSession session()  { return session; }
    public UserRow user()          { return user; }
    public BbsServices services()  { return services; }

    /** Transitional accessor for the still-private helpers on ScreenRouter. */
    public Object router()         { return legacyRouter; }
    public Object legacyRouter()   { return legacyRouter; }

    /** {@code true} when the session is authenticated. */
    public boolean isAuthenticated() {
        return user != null;
    }

    /** {@code true} when the authenticated user is a sysop. */
    public boolean isSysop() {
        return user != null && user.isSysop();
    }

    // ===================================================================
    // Outbound: send a server message to this session
    // ===================================================================

    /**
     * Send a {@link ServerMessage} to this session, swallowing
     * {@link IOException} with a debug log line.
     */
    public void send(ServerMessage m) {
        try {
            session.send(m);
        } catch (IOException e) {
            log.debug("send failed for session={}: {}", session.id(), e.toString());
        }
    }

    // ===================================================================
    // Navigation (Navigator interface — push/pop/replaceTop)
    // ===================================================================

    /** Push a phase onto the navigation stack and dispatch its {@code onEnter}. */
    public void push(Phase phase) {
        navigator.push(session, phase);
    }

    /**
     * Pop the top of the navigation stack. The screen beneath becomes
     * active; its {@code onEnter} re-fires. If popping would empty the
     * stack, the leaving screen's root guard fires (typically logout).
     */
    public void pop() {
        navigator.pop(session);
    }

    /**
     * Replace the top of the stack without re-painting. Used by pre-auth
     * linear flows (login → password → menu) where stack semantics don't
     * apply.
     */
    public void replaceTop(Phase phase) {
        navigator.replaceTop(session, phase);
    }

    /**
     * Replace the top of the stack and dispatch the new top's
     * {@code onEnter}. Used by linear, stack-flat workflows that step
     * forward without nesting (NetMail compose: TO → SUBJECT → BODY).
     */
    public void replaceTopAndEnter(Phase phase) {
        navigator.replaceTopAndEnter(session, phase);
    }

    // ===================================================================
    // Cross-cutting service helpers (delegated to BbsServices)
    // ===================================================================

    /**
     * Persist {@code sessions.current_screen} JSONB so a reconnect
     * lands the user back here. Per SPEC §3 / §13.
     */
    public void persistCurrentScreen(String screenJson) {
        services.persistCurrentScreen(session, screenJson);
    }

    /** Read the active user's saved theme name; default {@code phosphor}. */
    public String currentTheme() {
        return user == null ? "phosphor" : services.currentTheme(user.id());
    }

    /** Append a row to the sysop audit log; no-op pre-auth. */
    public void audit(String action, JsonNode payload) {
        services.audit(session, action, payload);
    }

    // ===================================================================
    // Cross-session messaging (ADR-027)
    // ===================================================================

    /**
     * Notify every subscriber of {@code topic} that the underlying
     * data changed. Payload-less per ADR-027 — listeners re-read
     * their source of truth (DB).
     *
     * <p>Typical usage: a screen mutates a resource (oneliner posted,
     * file uploaded, thread reply added) then publishes the topic so
     * peers viewing that resource re-paint.
     *
     * <pre>{@code
     * oneliners.insert(text, user.id());
     * ctx.publish("oneliners");
     * }</pre>
     */
    public void publish(String topic) {
        services.bus().notify(topic);
    }
}
