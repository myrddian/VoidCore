package io.aeyer.voidcore.ws.flow.screen;

import io.aeyer.voidcore.auth.AuthService;
import io.aeyer.voidcore.auth.AuthService.Outcome;
import io.aeyer.voidcore.auth.LoginSummary;
import io.aeyer.voidcore.auth.LoginSummaryService;
import io.aeyer.voidcore.auth.Session;
import io.aeyer.voidcore.auth.SessionService;
import io.aeyer.voidcore.auth.UserRepository;
import io.aeyer.voidcore.auth.UserRepository.UserRow;
import io.aeyer.voidcore.presence.PresenceEntry;
import io.aeyer.voidcore.presence.PresenceService;
import io.aeyer.voidcore.ws.VoidCoreSession;
import io.aeyer.voidcore.ws.SessionRegistry;
import io.aeyer.voidcore.ws.flow.Frames;
import io.aeyer.voidcore.ws.flow.RegisterDraft;
import io.aeyer.voidcore.ws.protocol.ClientMessage;
import io.aeyer.voidcore.ws.protocol.ServerMessage;
import io.aeyer.voidcore.ws.protocol.ServerMessage.AuthOk;
import io.aeyer.voidcore.ws.protocol.ServerMessage.UserSummary;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

/**
 * Auth completion: the post-login / post-register orchestration that
 * binds an authenticated session to its user record, broadcasts the
 * presence banner, sends the {@code auth.ok} envelope, and hands off
 * to {@link PostAuthLanding} for the deep-link / restore / menu
 * decision.
 *
 * <p>Extracted from {@link io.aeyer.voidcore.ws.flow.ScreenRouter} (v1.4
 * tail end) so the login/register screens can call this directly via
 * {@link BbsServices#authFinaliser()} without needing the
 * {@code legacy*} bridges. Brings the legacy bridge count to zero.
 *
 * <h2>Why a separate bean (Spring cycle workaround)</h2>
 *
 * <p>{@code ScreenRouter} depends on the {@code List<Screen>}, which
 * depends on {@code BbsServices}, which used to depend on
 * {@code ScreenRouter} (for {@code authSucceeded}). Pulling auth
 * finalisation into its own bean lets the screens reach it via
 * {@code BbsServices.authFinaliser()} without re-introducing the
 * cycle.
 *
 * <p>This bean still needs to call back into the router for one
 * thing: post-auth landing (intent → restore → menu). That's what
 * the small {@link PostAuthLanding} interface is for; we depend on it
 * via {@code @Lazy} so Spring can wire {@code ScreenRouter} (which
 * implements it) without a true circular construction. The
 * {@code @Lazy} surface is exactly one method
 * ({@link PostAuthLanding#applyPostAuth}) instead of the whole router
 * type — pragmatic minimal-cycle workaround until the deeper
 * screen-registry-as-leaf refactor lands (see ADR-030).
 */
@Component
@ConditionalOnBean(SessionService.class)
public class AuthFinaliser {

    private static final Logger log = LoggerFactory.getLogger(AuthFinaliser.class);
    private static final String[] THEME_CYCLE = {"phosphor", "amber", "cga", "modern"};

    private final AuthService auth;
    private final UserRepository users;
    private final PresenceService presence;
    private final SessionRegistry wsSessions;
    private final ObjectMapper json;
    private final Navigator navigator;
    private final PostAuthLanding postAuth;
    private final NavigationState navState;
    private final LoginSummaryService loginSummaries;
    private final io.aeyer.voidcore.social.SocialEventService socialEvents;

    public AuthFinaliser(AuthService auth,
                         UserRepository users,
                         PresenceService presence,
                         SessionRegistry wsSessions,
                         ObjectMapper json,
                         @Lazy Navigator navigator,
                         @Lazy PostAuthLanding postAuth,
                         NavigationState navState,
                         org.springframework.beans.factory.ObjectProvider<LoginSummaryService> loginSummaries,
                         org.springframework.beans.factory.ObjectProvider<io.aeyer.voidcore.social.SocialEventService> socialEvents) {
        this.auth = auth;
        this.users = users;
        this.presence = presence;
        this.wsSessions = wsSessions;
        this.json = json;
        this.navigator = navigator;
        this.postAuth = postAuth;
        this.navState = navState;
        this.loginSummaries = loginSummaries.getIfAvailable();
        this.socialEvents = socialEvents.getIfAvailable();
    }

    // ===================================================================
    // Public entry points (called from login/register screens)
    // ===================================================================

    /**
     * Pivot the login flow into new-user registration. Initialises a
     * fresh draft, paints the "NEW USER APPLICATION" framing, and
     * advances the navigator stack to {@link Phase#REGISTER_HANDLE}
     * via {@code replaceTopAndEnter} so the register chain runs flat
     * at the top of the stack — same shape as netmail compose / forum
     * compose.
     */
    public void startRegisterFlow(VoidCoreSession session) {
        session.setRegisterDraft(RegisterDraft.empty());
        sendQuiet(session, Frames.notify("notifications",
                "new caller — setting up your account ([Esc] to cancel)",
                "info", 2500));
        sendQuiet(session, Frames.update("main", 5, Frames.textRows(List.of(
                "",
                "  == NEW USER APPLICATION ==",
                "",
                "  Press [Esc] at any field to cancel and return to login.",
                "  Optional fields take [Enter] for empty.",
                ""
        ), "default")));
        navigator.replaceTopAndEnter(session, Phase.REGISTER_HANDLE);
    }

    /**
     * Dispatch credentials to {@link AuthService#login}. On success,
     * runs the full post-auth pipeline (attach user, theme effect,
     * presence register, banner broadcast, {@code auth.ok}, post-auth
     * landing). On failure, surfaces the error message and replaces
     * the stack top with {@link Phase#LOGIN_HANDLE} so the user can
     * retry from the handle prompt.
     */
    /**
     * Convenience overload: derives {@code ip} / {@code userAgent}
     * from {@link VoidCoreSession#underlying()} (remote address +
     * {@code User-Agent} header). The screen-side caller doesn't
     * need to plumb those itself.
     */
    public void handleLogin(VoidCoreSession session, ClientMessage.AuthLogin req) {
        handleLogin(session, req, ip(session), ua(session), null);
    }

    /**
     * Login walk called from the screen path (which has no inbound
     * envelope id to echo). Backwards-compat overload.
     */
    public void handleLogin(VoidCoreSession session, ClientMessage.AuthLogin req, String inboundId) {
        handleLogin(session, req, ip(session), ua(session), inboundId);
    }

    public void handleLogin(VoidCoreSession session,
                            ClientMessage.AuthLogin req,
                            String ip,
                            String userAgent,
                            String inboundId) {
        log.debug("[ws-trace] auth-login-attempt id={} handle={} ip={} inboundId={}",
                session.id(), req.handle(), ip, inboundId);
        Outcome o = auth.login(req, ip, userAgent);
        if (o instanceof AuthService.Failure f) {
            log.warn("[ws-trace] auth-login-fail id={} code={} msg={}",
                    session.id(), f.code(), f.message());
            sendQuietReply(session, AuthService.toAuthErr(f), inboundId);
            sendQuiet(session, Frames.notify("notifications",
                    f.message(), "alert", 3000));
            navigator.replaceTopAndEnter(session, Phase.LOGIN_HANDLE);
            return;
        }
        if (o instanceof AuthService.Success s) {
            log.debug("[ws-trace] auth-login-ok id={} userId={}",
                    session.id(), s.user().id());
            onSuccess(session, s.user(), s.session(),
                    s.priorLastCallAt(), inboundId);
        }
    }

    /**
     * Final step of the new-user registration walk: assemble the draft,
     * call {@link AuthService#register}, and either run the post-auth
     * pipeline (success) or surface the error and bounce to
     * {@link Phase#LOGIN_HANDLE} (failure).
     */
    /** Convenience overload — see {@link #handleLogin(VoidCoreSession, ClientMessage.AuthLogin)}. */
    public void finaliseRegistration(VoidCoreSession session, String favGenres) {
        // Screen-path entry: there's no inbound envelope id to echo
        // (the registration walk is multi-step keystrokes, not a
        // direct auth.register message).
        finaliseRegistration(session, favGenres, ip(session), ua(session));
    }

    public void finaliseRegistration(VoidCoreSession session,
                                     String favGenres,
                                     String ip,
                                     String userAgent) {
        RegisterDraft d = session.registerDraft().withFavGenres(favGenres);
        session.setRegisterDraft(null);

        ClientMessage.AuthRegister req = new ClientMessage.AuthRegister(
                d.handle(), d.password(), d.location(), d.setup(),
                d.foundVia(), d.favGenres());
        Outcome o = auth.register(req, ip, userAgent);
        if (o instanceof AuthService.Failure f) {
            sendQuiet(session, Frames.notify("notifications",
                    "registration failed: " + f.message(), "alert", 4000));
            // Re-base the stack so leftover REGISTER_* frames don't
            // dangle (the user returned to login but their stack
            // could still hold register screens otherwise).
            navigator.replaceTopAndEnter(session, Phase.LOGIN_HANDLE);
            return;
        }
        if (o instanceof AuthService.Success s) {
            sendQuiet(session, Frames.notify("notifications",
                    "welcome aboard, " + s.user().handle() + "!", "info", 3000));
            // Fresh register has no prior call → skip login-summary
            // (every doc would be "new" — noise, not signal).
            onSuccess(session, s.user(), s.session(), null, null);
        }
    }

    /**
     * Run the post-auth pipeline for a session that just succeeded
     * via {@link AuthService#register}. Public so the router's direct
     * {@code auth.register} dispatcher (typed wire path, distinct from
     * the multi-step register screens) can reuse the same finalisation
     * — there's no code duplication risk because everything lives
     * here.
     */
    public void onAuthSuccess(VoidCoreSession session, UserRow user, Session newSession) {
        onSuccess(session, user, newSession, null, null);
    }

    /**
     * Same as {@link #onAuthSuccess(VoidCoreSession, UserRow, Session)}
     * but echoes the inbound envelope's id on the {@code auth.ok}
     * response so the client can correlate (SPEC §4).
     */
    public void onAuthSuccess(VoidCoreSession session, UserRow user, Session newSession,
                              String inboundId) {
        onSuccess(session, user, newSession, null, inboundId);
    }

    // ===================================================================
    // Internal pipeline
    // ===================================================================

    /**
     * The unified post-auth pipeline:
     * <ol>
     *   <li>{@link #attachUser} — bind user-id and handle on the
     *       session and clear the transient {@code pendingLoginHandle}
     *       scratch field.</li>
     *   <li>Stamp the session token from the new {@link Session}.</li>
     *   <li>Send {@code effect.set_theme} so the client picks up the
     *       saved theme immediately.</li>
     *   <li>{@link PresenceService#register} this session and broadcast
     *       the join banner to every other authenticated session.</li>
     *   <li>Emit the {@code auth.ok} envelope (with the session token
     *       inlined so the client persists it — see SPEC §5).</li>
     *   <li>Hand off to {@link PostAuthLanding#applyPostAuth} for the
     *       intent / restore / menu decision.</li>
     * </ol>
     */
    private void onSuccess(VoidCoreSession session, UserRow user, Session newSession,
                           java.time.OffsetDateTime priorLastCallAt,
                           String inboundId) {
        log.debug("[ws-trace] auth-success id={} userId={} handle={} sysop={} inboundId={} priorCall={}",
                session.id(), user.id(), user.handle(), user.isSysop(),
                inboundId, priorLastCallAt);
        // Ticket #85: stash priorLastCallAt on the session so the
        // login-summary screen — pushed below when conditions match
        // — can compute deltas. Captured by AuthService BEFORE
        // recordCall bumped the DB value; null for fresh registers
        // (every doc would be "new" — noise, not signal).
        session.setPreviousLastCall(
                priorLastCallAt == null ? null : priorLastCallAt.toInstant());

        attachUser(session, user);
        session.setSessionToken(newSession.token());
        // ADR-033: register the token → actor mapping with the registry
        // so a future reconnect carrying this token can find the still-
        // alive actor (within the TTL window) and resume in place.
        wsSessions.rekeyOnAuth(session.id(), newSession.token());
        sendQuiet(session, new ServerMessage.EffectSetTheme(currentTheme(user.id())));
        PresenceEntry entry = presence.register(
                session.id(), user.id(), user.handle(), user.isSysop());
        broadcastPresenceBanner(entry, true, session.id());
        sendAuthOk(session, user, newSession, inboundId);
        log.debug("[ws-trace] auth-success-applyPostAuth id={} pendingIntent={}",
                session.id(), session.pendingIntent());
        postAuth.applyPostAuth(session, user);

        // Ticket #85: if applyPostAuth landed the user on MENU (no
        // intent, no restore), and the deltas since their last call
        // are non-empty, push the login-summary screen on top so it
        // catches their attention before the menu does. Skipped on
        // intent / restore paths so deep-links and resume don't get
        // a redundant interstitial.
        if (loginSummaries != null && navState.currentPhase(session) == io.aeyer.voidcore.ws.flow.screen.Phase.MENU) {
            LoginSummary summary = loginSummaries.compute(user.id(),
                    priorLastCallAt);
            if (!summary.isEmpty()) {
                log.debug("[ws-trace] auth-success-push-login-summary id={} total={}",
                        session.id(), summary.total());
                navigator.push(session,
                        io.aeyer.voidcore.ws.flow.screen.Phase.LOGIN_SUMMARY);
            }
        }

        // #89: evaluate caller-count milestones (first-login, caller-10,
        // caller-100). recordCall already bumped users.call_count
        // during AuthService.login, so the value reflects this very
        // session. Awarded achievements get surfaced to the session
        // as info notifies — non-blocking.
        if (socialEvents != null) {
            for (var a : socialEvents.checkCallerMilestones(user.id())) {
                sendQuiet(session, Frames.notify("notifications",
                        "★ Achievement unlocked: " + a.name(),
                        "info", 4000));
            }
        }
        log.debug("[ws-trace] auth-success-end id={}", session.id());
    }

    private void attachUser(VoidCoreSession session, UserRow user) {
        session.setUserId(user.id());
        session.setHandle(user.handle());
        // session.isSysop() is the source of truth for visibility / permission
        // checks (DocumentView.canRead) — set it alongside the other
        // user-bound fields so any session-only code path can read it.
        session.setSysop(user.isSysop());
        // Auth-success: drop the transient login-handle scratch state.
        // It served the password-prompt step; the authoritative handle
        // is now on session.handle().
        session.setPendingLoginHandle(null);
    }

    /** Read the user's saved theme from preferences JSONB; default phosphor. */
    private String currentTheme(long userId) {
        try {
            var node = json.readTree(users.preferences(userId));
            String t = node.path("theme").asText("phosphor");
            for (String known : THEME_CYCLE) if (known.equals(t)) return t;
            return "phosphor";
        } catch (Exception e) {
            return "phosphor";
        }
    }

    /**
     * Emit the {@code auth.ok} envelope with the session token inlined
     * into the payload so the client persists it (SPEC §5). The
     * protocol's {@code AuthOk} record doesn't formally have a token
     * field; we add it via a manual {@link ObjectNode} build so the
     * client picks it up without needing a record schema change.
     */
    private void sendAuthOk(VoidCoreSession session, UserRow user, Session newSession,
                            String inboundId) {
        AuthOk ok = new AuthOk(
                new UserSummary(user.id(), user.handle(), user.isSysop()),
                null);
        try {
            ObjectNode envPayload = (ObjectNode) json.valueToTree(ok);
            envPayload.put("token", newSession.token());
            ObjectNode env = json.createObjectNode();
            // Echo the inbound's id so the client can correlate this
            // response to its auth.login / auth.register message
            // (SPEC §4). Null only when called from screen paths that
            // have no inbound envelope to echo.
            if (inboundId == null) {
                env.putNull("id");
            } else {
                env.put("id", inboundId);
            }
            env.put("type", "auth.ok");
            env.put("protocol_version", "voidcore-node-v1");
            env.put("seq", 0);
            env.putNull("mac");
            env.set("payload", envPayload);
            session.underlying().sendMessage(
                    new org.springframework.web.socket.TextMessage(env.toString()));
        } catch (IOException e) {
            log.warn("could not send auth.ok with token: {}", e.toString());
        }
    }

    /**
     * Push a {@code *** HANDLE has logged on, node NN ***} (or
     * "logged off") banner to every other authenticated session.
     * Sessions still at the login prompt or post-goodbye don't see
     * it — we filter by {@code peer.userId() != null} (presence and
     * the auth pipeline guarantee userId is set iff the session has
     * passed the login phase).
     *
     * <p>Earlier this iterated {@code ScreenRouter.phases.entrySet()};
     * with the AuthFinaliser extraction we no longer have access to
     * that map. {@link SessionRegistry#all} is the authoritative
     * iteration source; the {@code userId() != null} filter replaces
     * the LOGIN_HANDLE / LOGIN_PASSWORD / GOODBYE phase exclusions —
     * pre-auth sessions have no userId, and post-logout sessions are
     * removed from the registry on disconnect.
     *
     * <p>Public so the disconnect path in {@link
     * io.aeyer.voidcore.ws.flow.ScreenRouter#onDisconnect} can reuse this
     * for the {@code joined=false} (leave) banner.
     */
    public void broadcastPresenceBanner(PresenceEntry entry,
                                        boolean joined,
                                        String triggeringSessionId) {
        String verb = joined ? "logged on" : "logged off";
        String text = entry.handle().toUpperCase()
                + " has " + verb + ", node " + String.format("%02d", entry.nodeNumber());
        for (VoidCoreSession peer : wsSessions.all()) {
            if (peer.id().equals(triggeringSessionId)) continue;
            if (peer.userId() == null) continue;
            if (!peer.isOpen()) continue;
            try {
                peer.send(Frames.notify("notifications", text,
                        joined ? "info" : "warn", 4000));
            } catch (IOException e) {
                log.debug("broadcast banner failed for session={}: {}",
                        peer.id(), e.toString());
            }
        }
    }

    private void sendQuiet(VoidCoreSession session, ServerMessage m) {
        try {
            session.send(m);
        } catch (IOException e) {
            // WARN: outbound write failed — user is missing UI updates.
            log.warn("send failed for session={}: {}", session.id(), e.toString());
        }
    }

    /**
     * Send a response message that echoes the inbound id for SPEC §4
     * request/response correlation (auth.err on login failure).
     * {@code inReplyTo == null} falls back to the regular send (no
     * correlation possible).
     */
    private void sendQuietReply(VoidCoreSession session, ServerMessage m, String inReplyTo) {
        try {
            session.sendInReplyTo(m, inReplyTo);
        } catch (IOException e) {
            log.warn("sendReply failed for session={}: {}", session.id(), e.toString());
        }
    }

    private static String ip(VoidCoreSession session) {
        var addr = session.underlying().getRemoteAddress();
        if (addr == null) return "127.0.0.1";
        // Strip the port + bracket if IPv6
        String host = addr.getAddress() == null ? "127.0.0.1" : addr.getAddress().getHostAddress();
        return host;
    }

    private static String ua(VoidCoreSession session) {
        var ua = session.underlying().getHandshakeHeaders().getFirst("User-Agent");
        return ua == null ? "" : ua;
    }
}
