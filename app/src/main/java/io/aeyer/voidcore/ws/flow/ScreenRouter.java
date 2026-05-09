package io.aeyer.voidcore.ws.flow;

import io.aeyer.voidcore.auth.AuthService;
import io.aeyer.voidcore.auth.AuthService.Outcome;
import io.aeyer.voidcore.auth.Session;
import io.aeyer.voidcore.auth.SessionService;
import io.aeyer.voidcore.auth.UserRepository;
import io.aeyer.voidcore.auth.UserRepository.UserRow;
import io.aeyer.voidcore.messages.MessageBaseRepository;
import io.aeyer.voidcore.messages.ThreadRepository;
import io.aeyer.voidcore.netmail.NetmailRepository;
import io.aeyer.voidcore.presence.PresenceService;
import io.aeyer.voidcore.ws.VoidCoreSession;
import io.aeyer.voidcore.ws.SessionRegistry;
import io.aeyer.voidcore.ws.flow.bus.MessageBus;
import io.aeyer.voidcore.ws.session.SessionProxy;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.BbsServices;
import io.aeyer.voidcore.ws.flow.screen.CustomScreenRegistry;
import io.aeyer.voidcore.ws.flow.screen.Frame;
import io.aeyer.voidcore.ws.flow.screen.NavigationState;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.flow.screen.Screen;
import io.aeyer.voidcore.ws.flow.screen.ScreenApp;
import io.aeyer.voidcore.ws.flow.screen.ScreenRoute;
import io.aeyer.voidcore.ws.flow.screen.Transition;
import io.aeyer.voidcore.ws.flow.ui.AppEvent;
import io.aeyer.voidcore.ws.protocol.ClientMessage;
import io.aeyer.voidcore.ws.protocol.ServerMessage;
import io.aeyer.voidcore.ws.protocol.ServerMessage.Row;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.web.socket.CloseStatus;

import java.io.IOException;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Server-side screen state machine. The smart-terminal client (see
 * {@code app/src/main/frontend}) renders whatever frames arrive — this class
 * decides what those frames are based on the user's current phase
 * (connect → login → menu → goodbye, or register-flow detour). Per SPEC §6:
 * application logic lives entirely on the server.
 *
 * <p>v1 menu options other than <strong>G</strong> (goodbye) emit a
 * {@code region.notify} saying "not implemented" — the wire to the user
 * is live, the sub-screens land in their own tickets.
 *
 * <p>State per connection lives on {@link NavigationState}, keyed by
 * {@link VoidCoreSession#id()}. The session's top-of-stack {@link Frame}
 * carries both the active {@link Phase} and the live {@link Screen}
 * instance servicing it; the dispatcher reads that frame to route
 * subsequent client messages.
 */
public class ScreenRouter
        implements io.aeyer.voidcore.ws.flow.screen.Navigator,
                   io.aeyer.voidcore.ws.flow.screen.PostAuthLanding {

    private static final Logger log = LoggerFactory.getLogger(ScreenRouter.class);

    private final AuthService auth;
    private final SessionService sessions;
    private final UserRepository users;
    private final NetmailRepository netmail;
    private final MessageBaseRepository messageBases;
    private final ThreadRepository threads;
    private final PresenceService presence;
    private final ObjectMapper json;
    private final SessionRegistry wsSessions;
    private final BbsServices bbsServices;
    private final MessageBus bus;
    private final NavigationState navState;
    private final CustomScreenRegistry customScreens;

    /**
     * Phase → screen-bean ObjectProvider registry, populated at
     * construction. Resolved through {@link ObjectProvider#getObject}
     * on every push so the bean's Spring scope decides the lifecycle:
     * <ul>
     *   <li>{@link io.aeyer.voidcore.ws.flow.screen.ScreenComponent} (singleton):
     *       same instance every push — fine for stateless screens.</li>
     *   <li>{@link io.aeyer.voidcore.ws.flow.screen.ScreenAppComponent}
     *       (prototype): fresh instance every push — required for
     *       {@code ScreenApp} subclasses that hold per-session state in
     *       instance fields, which would otherwise leak across sessions.</li>
     * </ul>
     * The minted instance is pinned to the navigator stack layer that
     * minted it (see {@link Frame}) so dispatch always targets the
     * right per-session instance.
     */
    private final Map<Phase, ObjectProvider<? extends Screen>> screenProviders;

    public ScreenRouter(AuthService auth, SessionService sessions,
                        UserRepository users,
                        NetmailRepository netmail,
                        MessageBaseRepository messageBases,
                        ThreadRepository threads,
                        PresenceService presence,
                        ObjectMapper json, SessionRegistry wsSessions,
                        BbsServices bbsServices,
                        MessageBus bus,
                        NavigationState navState,
                        ApplicationContext appCtx,
                        List<Screen> screenSentinels) {
        this(auth, sessions, users, netmail, messageBases, threads, presence, json,
                wsSessions, bbsServices, bus, navState, appCtx, screenSentinels,
                CustomScreenRegistry.empty());
    }

    public ScreenRouter(AuthService auth, SessionService sessions,
                        UserRepository users,
                        NetmailRepository netmail,
                        MessageBaseRepository messageBases,
                        ThreadRepository threads,
                        PresenceService presence,
                        ObjectMapper json, SessionRegistry wsSessions,
                        BbsServices bbsServices,
                        MessageBus bus,
                        NavigationState navState,
                        ApplicationContext appCtx,
                        List<Screen> screenSentinels,
                        CustomScreenRegistry customScreens) {
        this.auth = auth;
        this.sessions = sessions;
        this.users = users;
        this.netmail = netmail;
        this.messageBases = messageBases;
        this.threads = threads;
        this.presence = presence;
        this.json = json;
        this.wsSessions = wsSessions;
        this.bbsServices = bbsServices;
        this.bus = bus;
        this.navState = navState;
        this.customScreens = customScreens;
        Map<Phase, ObjectProvider<? extends Screen>> providers = new EnumMap<>(Phase.class);
        Map<Phase, String> names = new EnumMap<>(Phase.class);
        for (Screen sentinel : screenSentinels) {
            // The sentinel itself was minted by Spring to populate this
            // List<Screen>. For prototype-scoped screens it's a throwaway
            // we use only to read phase()+name() before discarding the
            // reference — real dispatch goes through the ObjectProvider.
            Phase phase = sentinel.phase();
            @SuppressWarnings({"rawtypes", "unchecked"})
            Class<? extends Screen> cls = (Class<? extends Screen>) sentinel.getClass();
            ObjectProvider<? extends Screen> provider = appCtx.getBeanProvider(cls);
            ObjectProvider<? extends Screen> prev = providers.put(phase, provider);
            if (prev != null) {
                throw new IllegalStateException(
                        "Two screens claim phase " + phase
                        + ": " + names.get(phase) + " and " + sentinel.name());
            }
            names.put(phase, sentinel.name());
        }
        this.screenProviders = Collections.unmodifiableMap(providers);
        // ADR-033: register a termination listener so per-session
        // cleanup runs when an actor is permanently shut down
        // (anon disconnect, TTL expiry, explicit logout) — NOT on every
        // WS close, so detach-then-reconnect-within-TTL preserves
        // nav state / bus subs / presence in place.
        wsSessions.addTerminationListener(this::onSessionTerminated);
        log.info("ScreenRouter initialised with {} extracted screens (of {} total phases) and {} custom screen registrations; rest fall back to legacy dispatch",
                providers.size(), Phase.values().length, customScreens.names().size());
    }

    /**
     * Mint a fresh frame for {@code phase}. For prototype-scoped screen
     * beans this creates a new instance per call (the per-session state
     * lives on it for the duration of the navigator-stack layer); for
     * singleton-scoped screens this returns the same shared instance
     * every call. {@code null} screen if the phase has no registered
     * provider yet (legacy phases), matching the old map-lookup contract.
     */
    private Frame mintFrame(Phase phase) {
        return mintFrame(ScreenRoute.core(phase));
    }

    private Frame mintFrame(String screenName) {
        return mintFrame(ScreenRoute.custom(screenName));
    }

    private Frame mintFrame(ScreenRoute route) {
        if (route instanceof ScreenRoute.Core core) {
            Phase phase = core.phase();
            ObjectProvider<? extends Screen> provider = screenProviders.get(phase);
            Screen screen = provider == null ? null : provider.getObject();
            return new Frame(route, screen);
        }
        if (route instanceof ScreenRoute.Custom custom) {
            Screen screen = customScreens.create(custom.screenName()).orElse(null);
            return new Frame(route, screen);
        }
        throw new IllegalStateException("Unhandled route type " + route.getClass().getName());
    }

    /** Top-of-stack screen for {@code session}, or {@code null}. */
    private Screen activeScreen(VoidCoreSession session) {
        Frame top = navState.peekFrame(session);
        return top == null ? null : top.screen();
    }

    /**
     * Permanent shutdown of an actor — wired as a termination listener
     * on {@link SessionRegistry}. Runs the full per-session cleanup
     * (phases / nav state / bus subscriptions / presence). Replaces
     * the v1.4 {@code onDisconnect} that used to fire on every WS
     * close. Per ADR-033 this fires only when the actor is truly going
     * away.
     */
    private void onSessionTerminated(io.aeyer.voidcore.ws.session.SessionProxy proxy) {
        log.debug("[ws-trace] router-onSessionTerminated id={} userId={} handle={}",
                proxy.id(), proxy.userId(), proxy.handle());
        navState.clear(proxy);
        bus.unsubscribeAll(proxy.id());
        presence.unregister(proxy.id())
                .ifPresent(entry -> bbsServices.authFinaliser()
                        .broadcastPresenceBanner(entry, false, proxy.id()));
        log.debug("[ws-trace] router-onSessionTerminated-end id={}", proxy.id());
    }

    /**
     * Build a {@link BbsContext} for the given session. Used by the new
     * dispatch path when delegating to extracted {@link Screen}
     * implementations. The {@code router} reference exposed via the
     * context is this instance — extracted screens still need access
     * to legacy helpers (audit, persistCurrentScreen, etc.) during the
     * incremental migration; once all screens are extracted, those
     * helpers move to a dedicated service bag.
     */
    private BbsContext makeContext(VoidCoreSession session) {
        UserRow user = session.userId() == null
                ? null
                : users.findById(session.userId()).orElse(null);
        Frame frame = navState.peekFrame(session);
        String screenName = frame == null || frame.screen() == null ? null : frame.screen().name();
        return new BbsContext(session, user, this, bbsServices, this, screenName);
    }

    // ===================================================================
    // Navigator implementation (v1.4 PR-B step 4): stack-based navigation
    // ===================================================================

    @Override
    public void push(VoidCoreSession session, Phase phase) {
        pushRoute(session, ScreenRoute.core(phase));
    }

    @Override
    public void push(VoidCoreSession session, String screenName) {
        pushRoute(session, ScreenRoute.custom(screenName));
    }

    private boolean pushRoute(VoidCoreSession session, ScreenRoute route) {
        log.debug("[ws-trace] phase-push id={} from={} to={}",
                session.id(), currentRouteKey(session), route.key());
        Frame frame = mintFrame(route);
        if (frame.screen() == null) {
            log.warn("ignoring push to unregistered screen route '{}' for session={}",
                    route.key(), session.id());
            return false;
        }
        navState.pushFrame(session, frame);
        Screen screen = frame.screen();
        // CRITICAL: clear bus subscriptions BEFORE calling onEnter.
        // The previous screen's listeners would otherwise still be
        // attached to this session id. If the new screen's onEnter
        // calls ctx.publish, the old listener fires synchronously
        // and dispatches via the active screen — which is already
        // the new top, so it routes back into the same screen's
        // onEvent → onEnter → recursive loop. Concrete repro from
        // [ws-trace]:
        //   restoreFromCurrentScreen("thread") cascades:
        //     push BASES_LIST   (no topics, no leftovers)
        //     push THREADS_LIST (subscribes "base:N")
        //     push THREAD_VIEW  → onEnter publishes "base:N" → loop
        // Fix: subscribeBefore-onEnter ordering. Defensive
        // unsubscribeAll inside applyScreenSubscriptions stays as
        // a belt for the new top's resubscribe.
        bus.unsubscribeAll(session.id());
        log.debug("[ws-trace] phase-onEnter id={} screen={} phase={}",
                session.id(), screen.name(), route.key());
        BbsContext ctx = makeContext(session);
        screen.onEnter(ctx);
        applyScreenSubscriptions(session, screen, ctx);
        return true;
    }

    @Override
    public void pop(VoidCoreSession session) {
        Frame leaving = navState.popFrame(session);
        if (leaving == null) {
            log.debug("[ws-trace] phase-pop-empty id={}", session.id());
            return;
        }
        if (navState.isEmpty(session)) {
            log.debug("[ws-trace] phase-pop-root id={} leaving={} → logout",
                    session.id(), leaving.route().key());
            onAuthLogout(session);
            return;
        }
        Frame top = navState.peekFrame(session);
        Screen screen = top == null ? null : top.screen();
        log.debug("[ws-trace] phase-pop id={} from={} to={}",
                session.id(), leaving.route().key(), top == null ? null : top.route().key());
        if (screen != null) {
            // Same ordering rule as push() — clear leaving screen's
            // subscriptions before the popped-to screen's onEnter runs,
            // so any ctx.publish in onEnter doesn't fire the old
            // listener.
            bus.unsubscribeAll(session.id());
            log.debug("[ws-trace] phase-onEnter id={} screen={} phase={} (re-entry from pop)",
                    session.id(), screen.name(), top.route().key());
            BbsContext ctx = makeContext(session);
            screen.onEnter(ctx);
            applyScreenSubscriptions(session, screen, ctx);
        } else {
            bus.unsubscribeAll(session.id());
        }
    }

    @Override
    public void replaceTop(VoidCoreSession session, Phase phase) {
        log.debug("[ws-trace] phase-replaceTop id={} from={} to={} (no-onEnter)",
                session.id(), currentRouteKey(session), phase.name());
        navState.replaceTopFrame(session, mintFrame(phase));
        bus.unsubscribeAll(session.id());
    }

    @Override
    public void replaceTopAndEnter(VoidCoreSession session, Phase phase) {
        log.debug("[ws-trace] phase-replaceTopAndEnter id={} from={} to={}",
                session.id(), currentRouteKey(session), phase.name());
        replaceTop(session, phase);
        Frame top = navState.peekFrame(session);
        Screen screen = top == null ? null : top.screen();
        if (screen != null) {
            // replaceTop above already called bus.unsubscribeAll, so the
            // pre-onEnter clean is already done. No additional clear
            // needed before screen.onEnter.
            log.debug("[ws-trace] phase-onEnter id={} screen={} phase={} (replaceTopAndEnter)",
                    session.id(), screen.name(), phase.name());
            BbsContext ctx = makeContext(session);
            screen.onEnter(ctx);
            applyScreenSubscriptions(session, screen, ctx);
        }
    }

    /**
     * Install the new top's bus subscriptions after its {@code onEnter}
     * has fired. Per ADR-027.
     *
     * <p>Note: callers (push / pop / replaceTopAndEnter) are responsible
     * for calling {@code bus.unsubscribeAll(session.id())} BEFORE invoking
     * {@code onEnter} so that {@code ctx.publish} during onEnter doesn't
     * deliver to the previous screen's still-active listeners — which
     * would dispatch via {@code currentPhase(live)} into the new screen
     * and recurse. The defensive unsubscribeAll here is a no-op in the
     * happy path but stays as a belt against any code path that forgets.
     */
    private void applyScreenSubscriptions(VoidCoreSession session, Screen screen, BbsContext ctx) {
        bus.unsubscribeAll(session.id());
        List<String> topics = screen.topics(ctx);
        if (topics == null || topics.isEmpty()) return;
        for (String topic : topics) {
            // Closure rebuilds a fresh BbsContext on each delivery —
            // user / theme / etc. may have changed since subscribe.
            //
            // Delivery is ASYNC via the recipient session's actor. The
            // publisher (whoever called bus.publish) is itself running on
            // some session's actor worker thread. If we ran onEvent
            // synchronously on that thread, every state access on `live`
            // would block the publisher's thread on the recipient's
            // queue — which is the publish → onEvent → publish recursion
            // class of bug v1.4 hit (see DECISIONS.md ADR-033). By
            // enqueueing on the recipient's actor, the publisher returns
            // immediately and the recipient processes the event on its
            // own thread.
            bus.subscribe(session.id(), topic, () -> {
                VoidCoreSession live = wsSessions.get(session.id());
                if (live == null || !live.isOpen()) return;
                if (live instanceof SessionProxy proxy) {
                    proxy.actor().enqueueAsync(c -> {
                        // Re-check active frame + open state on the actor
                        // thread — state may have changed between publish
                        // and delivery (the whole reason this is async).
                        VoidCoreSession self = wsSessions.get(session.id());
                        if (self == null || !self.isOpen()) return null;
                        Screen current = activeScreen(self);
                        if (current == null) return null;
                        current.onEvent(makeContext(self), topic);
                        return null;
                    });
                } else {
                    // Non-proxy implementation (test fakes etc.) — fall
                    // back to synchronous delivery.
                    Screen current = activeScreen(live);
                    if (current == null) return;
                    current.onEvent(makeContext(live), topic);
                }
            });
        }
    }

    @Override
    public Phase currentPhase(VoidCoreSession session) {
        return navState.currentPhase(session);
    }

    @Override
    public ScreenRoute currentRoute(VoidCoreSession session) {
        return navState.currentRoute(session);
    }

    private String currentRouteKey(VoidCoreSession session) {
        ScreenRoute route = navState.currentRoute(session);
        return route == null ? null : route.key();
    }

    /**
     * Seed the stack with a fresh starting phase. Used by
     * {@code authSucceeded} / {@code restoreFromCurrentScreen} after
     * pre-auth flows hand off to normal stack-based navigation.
     */
    public void resetStack(VoidCoreSession session, Phase initial) {
        navState.resetFrame(session, mintFrame(initial));
    }

    /**
     * Apply the {@link Transition} a {@link Screen} returned. Mostly a
     * no-op during PR-A — extracted screens still call legacy paint
     * helpers directly and return {@link Transition.None}. Real
     * transition handling (back stack, To-with-args, screen stack)
     * lands in PR-A's later cleanup commits.
     */
    private void applyTransition(VoidCoreSession session, Transition t) {
        switch (t) {
            case Transition.None n -> { /* screen handled it directly */ }
            case Transition.Stay s -> { /* PR-B: render layout tree */ }
            case Transition.To to -> {
                // Extracted screens use ctx.push / ctx.replaceTopAndEnter;
                // no return-value-driven phase changes remain in the
                // codebase. Kept for sealed-interface exhaustiveness.
            }
            case Transition.Back b -> { /* screen stack — PR-A cleanup */ }
            case Transition.End e -> session.close(CloseStatus.NORMAL.withReason(e.reason()));
        }
    }

    // --- Lifecycle hooks called by BbsWebSocketHandler ------------------

    public void onConnect(VoidCoreSession session) {
        log.debug("[ws-trace] router-onConnect id={}", session.id());
        // LoginHandleScreen.onEnter owns the banner + connect-sequence
        // paint so every entry into LOGIN_HANDLE — initial connect,
        // auto-reconnect after a dropped WS, auth failure, logout —
        // gets a clean main region. Previously the paint lived here and
        // bounce-back-to-login paths skipped it, leaving stale post-auth
        // content visible.
        push(session, Phase.LOGIN_HANDLE);
        log.debug("[ws-trace] router-onConnect-end id={}", session.id());
    }

    // ScreenRouter.onDisconnect deleted in ADR-033 phase 1: per-session
    // cleanup now flows from SessionRegistry's termination listener
    // (onSessionTerminated above) which fires only when an actor is
    // permanently shut down — anon disconnect, TTL expiry, explicit
    // logout. WS-close alone no longer triggers cleanup so a brief
    // reconnect within TTL preserves all in-flight state in place.

    // --- Dispatcher entry points ---------------------------------------

    public void onAuthLogin(VoidCoreSession session, ClientMessage.AuthLogin req, String inboundId) {
        log.debug("[ws-trace] router-onAuthLogin id={} handle={} inboundId={}",
                session.id(), req.handle(), inboundId);
        // Direct login (e.g. test / future direct API). Two-line form
        // below (handle then password) is the user-facing path — but
        // the typed message variant is supported here too.
        // AuthFinaliser owns auth.login + post-auth pipeline.
        bbsServices.authFinaliser().handleLogin(session, req, inboundId);
        log.debug("[ws-trace] router-onAuthLogin-end id={}", session.id());
    }

    public void onAuthRegister(VoidCoreSession session, ClientMessage.AuthRegister req, String inboundId) {
        log.debug("[ws-trace] router-onAuthRegister id={} handle={} inboundId={}",
                session.id(), req.handle(), inboundId);
        // Direct register (typed wire path, distinct from the multi-
        // step register screens which call AuthFinaliser via the
        // BbsServices facade). Result handling lives in AuthFinaliser
        // — including the post-auth pipeline on success.
        Outcome o = auth.register(req, ip(session), ua(session));
        if (o instanceof AuthService.Failure f) {
            log.warn("[ws-trace] router-onAuthRegister-fail id={} code={} msg={}",
                    session.id(), f.code(), f.message());
            sendError(session, f, inboundId);
            return;
        }
        if (o instanceof AuthService.Success s) {
            log.debug("[ws-trace] router-onAuthRegister-ok id={} userId={}",
                    session.id(), s.user().id());
            bbsServices.authFinaliser().onAuthSuccess(session, s.user(), s.session(), inboundId);
        }
        log.debug("[ws-trace] router-onAuthRegister-end id={}", session.id());
    }

    public void onAuthResume(VoidCoreSession session, ClientMessage.AuthResume req, String inboundId) {
        log.debug("[ws-trace] router-onAuthResume id={} token-present={} intent={} inboundId={}",
                session.id(), req.token() != null && !req.token().isBlank(), req.intent(), inboundId);
        // Always cache the intent — even a null-token resume is the
        // client's way of delivering the URL fragment for SPEC §4.6.
        if (req.intent() != null && !req.intent().isBlank()) {
            session.setPendingIntent(req.intent());
        }

        if (req.token() == null || req.token().isBlank()) {
            log.debug("[ws-trace] router-onAuthResume-no-token id={} (intent-only stash)", session.id());
            return;
        }

        // ADR-033 AttachWs path: if a detached actor exists for this
        // token (i.e. the user reconnected within the TTL window), swap
        // the new WS into it and resume in place. The anon actor that
        // afterConnectionEstablished created for this WS is terminated
        // by attachExistingByToken — so for the rest of this call we
        // operate on the existing actor's proxy, not `session`.
        var existingOpt = wsSessions.attachExistingByToken(req.token(), session.underlying());
        if (existingOpt.isPresent()) {
            VoidCoreSession existing = existingOpt.get();
            log.info("[ws-trace] router-onAuthResume-attached actorId={} from-anon={}",
                    existing.id(), session.id());
            // Re-paint the FULL nav stack so every layer's region
            // content is on the new WS. Just re-entering the top phase
            // isn't enough — many screens' onEnter only emits the input
            // prompt and assumes the parent's main-region content is
            // already there (e.g. ComposePostBodyScreen.onEnter shows
            // "body:" prompt; the thread it's replying to was painted
            // by ThreadViewScreen earlier in the stack). On the fresh
            // WS the LoginHandleScreen.onEnter just painted the connect
            // sequence, so without walking the stack we'd leave that
            // visible underneath the body-prompt. No applyPostAuth
            // either — that resets to MENU and clobbers breadcrumbs.
            sendReply(existing, new ServerMessage.ResumeOk(false, List.of()), inboundId);
            send(existing, new ServerMessage.EffectSetTheme(bbsServices.currentTheme(existing.userId())));
            List<Frame> stackBottomToTop = navState.snapshotFramesBottomToTop(existing);
            if (stackBottomToTop.isEmpty()) {
                // No frame on the actor (corner case — sweep timing,
                // never finished post-auth before the previous WS
                // dropped). Fall back to a clean menu.
                log.debug("[ws-trace] router-onAuthResume-attached-empty-stack id={} → menu",
                        existing.id());
                UserRow u = users.findById(existing.userId()).orElse(null);
                if (u != null) showMainMenu(existing, u);
            } else {
                log.debug("[ws-trace] router-onAuthResume-attached-repaint id={} stack-depth={}",
                        existing.id(), stackBottomToTop.size());
                BbsContext ctx = makeContext(existing);
                Frame topFrame = stackBottomToTop.get(stackBottomToTop.size() - 1);
                // Walk the live frames; each carries the actual per-session
                // Screen instance that was minted on push, so re-running
                // onEnter targets the right instance and preserves any
                // state held on it.
                for (Frame layer : stackBottomToTop) {
                    Screen screen = layer.screen();
                    if (screen == null) continue;
                    screen.onEnter(ctx);
                }
                // Re-install the bus subscriptions for the top frame
                // (the only one whose listeners are live).
                Screen topScreen = topFrame.screen();
                if (topScreen != null) {
                    applyScreenSubscriptions(existing, topScreen, ctx);
                }
            }
            return;
        }

        // FALL-THROUGH: no still-alive actor for this token. Validate
        // against the DB and either land a fresh resume or reject.
        var maybe = auth.resume(req.token());
        if (maybe.isEmpty()) {
            log.debug("[ws-trace] router-onAuthResume-expired id={} sending resume.err", session.id());
            sendReply(session,
                    new ServerMessage.ResumeErr("AUTH_REQUIRED", "session expired"),
                    inboundId);
            return;
        }
        Session resumed = maybe.get();
        UserRow user = users.findById(resumed.userId()).orElseThrow(
                () -> new IllegalStateException(
                        "session resumed for missing user_id=" + resumed.userId()));
        log.debug("[ws-trace] router-onAuthResume-ok id={} userId={} handle={}",
                session.id(), user.id(), user.handle());
        attachUser(session, user);
        session.setSessionToken(resumed.token());
        // ADR-033: index this anon actor under the resumed token so
        // future reconnects can find it.
        wsSessions.rekeyOnAuth(session.id(), resumed.token());
        send(session, new ServerMessage.EffectSetTheme(bbsServices.currentTheme(user.id())));
        // sync=true would skip frames if we tracked region versions; for
        // the v1 thin-slice we always re-send the menu so a refresh lands
        // in a known-good state. Full region_versions reconciliation lands
        // with #28.
        sendReply(session, new ServerMessage.ResumeOk(false, List.of()), inboundId);
        applyPostAuth(session, user);
        log.debug("[ws-trace] router-onAuthResume-end id={}", session.id());
    }

    public void onAuthLogout(VoidCoreSession session) {
        log.debug("[ws-trace] router-onAuthLogout id={} userId={} handle={}",
                session.id(), session.userId(), session.handle());
        Frame goodbye = mintFrame(Phase.GOODBYE);
        navState.resetFrame(session, goodbye);
        Screen screen = goodbye.screen();
        if (screen != null) screen.onEnter(makeContext(session));
        String token = session.sessionToken();
        if (token != null) auth.logout(token);
        log.debug("[ws-trace] router-onAuthLogout-closing id={} (code=1000 goodbye)", session.id());
        session.close(CloseStatus.NORMAL.withReason("goodbye"));
        // Explicit logout: terminate the actor immediately, no TTL grace.
        // afterConnectionClosed → detach would leave the (now-anon, since
        // we cleared the auth-side token via auth.logout) actor briefly
        // alive — but more importantly the user explicitly asked to log
        // out, so any in-flight state should be discarded right now.
        // close() runs the full termination cleanup via the registry's
        // listener pipeline.
        wsSessions.close(session.id());
    }

    public void onLineSubmit(VoidCoreSession session, ClientMessage.LineSubmit req) {
        Frame top = navState.peekFrame(session);
        Phase ph = top == null ? null : top.phase();
        log.debug("[ws-trace] router-onLineSubmit id={} route={} text-len={}",
                session.id(), top == null ? null : top.route().key(),
                req.text() == null ? 0 : req.text().length());
        if (top == null) {
            log.debug("[ws-trace] router-onLineSubmit-ignored id={} (no phase)", session.id());
            return;
        }
        Screen screen = top.screen();
        if (screen != null) {
            log.debug("[ws-trace] router-onLineSubmit-→screen id={} screen={}",
                    session.id(), screen.name());
            Transition t = screen.onLine(makeContext(session), req.text());
            log.debug("[ws-trace] router-onLineSubmit-result id={} screen={} transition={}",
                    session.id(), screen.name(), t.getClass().getSimpleName());
            applyTransition(session, t);
            return;
        }
        switch (ph) {
            // LOGIN_HANDLE / LOGIN_PASSWORD handled by Login*Screen
            // (extracted in v1.4 PR-A2).
            // ONELINERS / CHAT_ROOM line submit handled by their respective
            // screens (extracted in v1.4 PR-A7).
            // NETMAIL_COMPOSE_* line submit handled by NetmailCompose*Screen
            // (extracted in v1.4 PR-A8).
            // --- Sysop tools (#40) ---
            // SYSOP_USER_BAN_REASON / SYSOP_USER_RESET_PW handled by
            // sysop user screens (extracted in v1.4 PR-A11).
            // SYSOP_BULLETIN_NEW_* handled by SysopBulletinNew*Screen (PR-A12).
            // SYSOP_FILE_EDIT_* / SYSOP_FILE_DELETE_CONFIRM / SYSOP_FILE_NEW_*
            // (18 line-input phases) handled by SysopFile*Screen extracted in v1.4 PR-A13.
            // SYSOP_BROADCAST handled by SysopBroadcastScreen (PR-A12).
            // --- Forum compose ---
            // COMPOSE_THREAD_SUBJECT / COMPOSE_THREAD_BODY / COMPOSE_POST_BODY
            // handled by Compose*Screen (extracted in v1.4 PR-A10).
            // --- Register multi-step form ---
            // REGISTER_* (6 phases) handled by Register*Screen
            // (extracted in v1.4 PR-A3).
            default -> {
                // Lines submitted when not in a line mode are ignored.
            }
        }
    }

    private static String optional(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public void onLineCancel(VoidCoreSession session) {
        Frame top = navState.peekFrame(session);
        Phase ph = top == null ? null : top.phase();
        log.debug("[ws-trace] router-onLineCancel id={} route={}",
                session.id(), top == null ? null : top.route().key());
        if (top != null) {
            Screen screen = top.screen();
            if (screen != null) {
                log.debug("[ws-trace] router-onLineCancel-→screen id={} screen={}",
                        session.id(), screen.name());
                Transition t = screen.onCancel(makeContext(session));
                log.debug("[ws-trace] router-onLineCancel-result id={} screen={} transition={}",
                        session.id(), screen.name(), t.getClass().getSimpleName());
                applyTransition(session, t);
                return;
            }
            // Esc from a content screen drops drafts and returns somewhere
            // sensible — chat/oneliners go back to menu, netmail compose
            // goes back to the inbox (so the user doesn't lose track of
            // where they were).
            switch (ph) {
                // ONELINERS / CHAT / CHAT_ROOM cancel handled by their screens.
                // NETMAIL_COMPOSE_* cancel handled by NetmailCompose*Screen (PR-A8).
                // SYSOP_USER_BAN_REASON / SYSOP_USER_RESET_PW cancel handled
                // by sysop user screens (PR-A11).
                // SYSOP_BULLETIN_NEW_* cancel handled by SysopBulletinNew*Screen (PR-A12).
                // SYSOP_FILE_EDIT_* / SYSOP_FILE_DELETE_CONFIRM cancel handled by
                // their Screen classes (extracted in v1.4 PR-A13).
                // SYSOP_FILE_NEW_* cancel handled by SysopFileNew*Screen (PR-A13).
                // SYSOP_BROADCAST cancel handled by SysopBroadcastScreen (PR-A12).
                // COMPOSE_THREAD_* / COMPOSE_POST_BODY cancel handled by
                // Compose*Screen (extracted in v1.4 PR-A10).
                default -> { /* fall through */ }
            }
        }
        // Default — cancel back to login (covers register flow + login phases).
        session.setRegisterDraft(null);
        replaceTopAndEnter(session, Phase.LOGIN_HANDLE);
    }

    public void onKeystroke(VoidCoreSession session, ClientMessage.Keystroke req) {
        Frame top = navState.peekFrame(session);
        Phase ph = top == null ? null : top.phase();
        String k = req.key() == null ? "" : req.key().toUpperCase();
        log.debug("[ws-trace] router-onKeystroke id={} route={} key={}",
                session.id(), top == null ? null : top.route().key(), k);
        if (top == null) {
            log.debug("[ws-trace] router-onKeystroke-ignored id={} (no phase)", session.id());
            return;
        }
        Screen screen = top.screen();
        if (screen != null) {
            log.debug("[ws-trace] router-onKeystroke-→screen id={} screen={} key={}",
                    session.id(), screen.name(), k);
            Transition t = screen.onKey(makeContext(session), k);
            log.debug("[ws-trace] router-onKeystroke-result id={} screen={} key={} transition={}",
                    session.id(), screen.name(), k, t.getClass().getSimpleName());
            applyTransition(session, t);
            return;
        }
        switch (ph) {
            // MENU handled by MenuScreen (extracted in v1.4 PR-A4).
            // BULLETINS_LIST / BULLETINS_VIEW handled by Bulletin*Screen
            // (extracted in v1.4 PR-A5).
            // RELEASES_LIST / RELEASES_VIEW handled by File*Screen
            // (extracted in v1.4 PR-A6).
            // NETMAIL_INBOX / NETMAIL_READ handled by Netmail*Screen
            // (extracted in v1.4 PR-A8).
            // SYSOP_MENU / SYSOP_USERS / SYSOP_USER_DETAIL handled by
            // sysop screens (extracted in v1.4 PR-A11).
            // SYSOP_BULLETINS handled by SysopBulletinsScreen (PR-A12).
            // SYSOP_RELEASES / SYSOP_RELEASE_EDIT handled by their Screen
            // classes (extracted in v1.4 PR-A13).
            // BASES_LIST / THREADS_LIST / THREAD_VIEW handled by forum
            // screens (extracted in v1.4 PR-A9).
            // INFO_VIEW handled by InfoViewScreen (extracted in v1.4 PR-A).
            default -> { /* ignored in handle/password/goodbye phases */ }
        }
    }

    /**
     * Dispatch a widget-level {@link AppEvent} to the active screen if it is
     * a {@link ScreenApp}. Called by {@code RoutingMessageDispatcher} for
     * the five A3 wire variants (editor.commit, editor.cancel,
     * editor.snapshot, field.commit, focus.move). If the active screen is
     * a plain {@link Screen} (not a ScreenApp), the event is logged and
     * dropped — these messages can only arrive while a ScreenApp is on top.
     */
    public void onAppEvent(VoidCoreSession session, AppEvent ev) {
        Frame top = navState.peekFrame(session);
        Phase ph = top == null ? null : top.phase();
        log.debug("[ws-trace] router-onAppEvent id={} route={} event={}",
                session.id(), top == null ? null : top.route().key(), ev.getClass().getSimpleName());
        if (top == null) {
            log.debug("[ws-trace] router-onAppEvent-ignored id={} (no phase)", session.id());
            return;
        }
        Screen active = top.screen();
        if (active instanceof ScreenApp app) {
            log.debug("[ws-trace] router-onAppEvent-→screenApp id={} screen={} event={}",
                    session.id(), app.name(), ev.getClass().getSimpleName());
            app.onAppEvent(makeContext(session), ev);
        } else {
            log.debug("[ws-trace] router-onAppEvent-dropped id={} event={} active={}",
                    session.id(), ev.getClass().getSimpleName(),
                    active == null ? "null" : active.getClass().getSimpleName());
        }
    }

    // handleMenuKey moved to MenuScreen (v1.4 PR-A4).

    // --- Releases ------------------------------------------------------

    // Files key handlers moved to File*Screen (v1.4 PR-A6).

    // showFilesList / showFileView / handleListenLink /
    // broadcastFileListUpdate / sendFileListFrames deleted in PR-B step 16.
    // ReleasesListScreen + ReleaseViewScreen own rendering + the listen-link
    // path; DocumentView (ADR-029) owns the cached read; bus.notify
    // ("documents") handles cross-session live update.
    // currentFileBySession map deleted; the breadcrumb lives on
    // VoidCoreSession.currentReleaseId now.

    // --- Frame builders -------------------------------------------------

    // showConnectSequence moved to LoginHandleScreen.paintConnectScreen
    // (this PR's stale-paint fix) — single source of truth so every entry
    // into LOGIN_HANDLE re-paints the banner + connect text. Previously
    // bounce-back-to-login paths skipped it, leaving stale post-auth
    // content visible alongside the login prompt.

    // showLoginHandlePrompt + showPasswordPrompt deleted in PR-B step 24 —
    // each screen's onEnter sends its own prompt directly.

    // New-user registration walk + handleRegisterFavGenres moved to
    // AuthFinaliser (post-PR-B AuthFinaliser extraction). Each
    // Register*Screen still owns its own paint + validation + draft
    // update + replaceTopAndEnter(NEXT); the final finalise call
    // (assemble draft → auth.register → post-auth pipeline / failure
    // bounce) lives on AuthFinaliser, reachable from screens via
    // BbsServices.authFinaliser().

    /**
     * Delegate to {@link io.aeyer.voidcore.ws.flow.screen.impl.MenuScreen} which
     * owns the menu rendering after PR-B step 7. Sets phase + dispatches
     * onEnter via the registry. Called by post-auth landing
     * (applyPostAuth) and the restore / intent paths.
     */
    private void showMainMenu(VoidCoreSession session, UserRepository.UserRow user) {
        // Seed the navigation stack with MENU at root. Without this,
        // a sub-screen's ctx.pop() on [Q] / [Esc] would empty the
        // stack and the root-guard would logout instead of returning
        // to the menu. The stack contract: MENU is always at the
        // bottom; popping MENU itself is the explicit logout. Per
        // ADR-026.
        resetStack(session, Phase.MENU);
        Screen menuScreen = activeScreen(session);
        if (menuScreen != null) menuScreen.onEnter(makeContext(session));
    }

    // currentTheme moved to BbsServices.currentTheme (single source
    // of truth — was already there for screens; the duplicate router
    // copy went away with the AuthFinaliser extraction).

    // showBulletinsList / showBulletinView deleted in PR-B step 15:
    // both screens own their rendering now, restore + intent paths
    // navigate via the Navigator stack (push BULLETINS_LIST,
    // optionally push BULLETINS_VIEW with currentBulletinId set).
    // Bulletin data flows through the DocumentView cache per ADR-029.

    // truncate / padRight static delegates deleted in PR-B step 24
    // (no remaining router-side paint code uses them; screens
    // import ScreenText directly).

    // showOneliners delegate deleted in PR-B step 24 (restore path
    // pushes ONELINERS via the navigator now).

    // Chat owns room selection, room rendering, validation,
    // rate-limit, room-scoped ctx.publish(topic), and peer repaint
    // via ChatView's ADR-029 cache.

    // NetMail private handlers deleted in PR-B step 19:
    // Netmail*Screen now own paint, key dispatch, mark-read,
    // soft-delete, reply pre-fill, the linear compose chain
    // (TO → SUBJECT → BODY via replaceTopAndEnter), recipient
    // bus.notify(NetmailInboxScreen.topicFor(uid)), and the
    // targeted "new netmail" popup via
    // BbsServices.mentions().notifyUser(...).

    // requireSysop / audit deleted in PR-B step 24 (no callers —
    // screens use ctx.isSysop() and ctx.audit(...) via BbsServices).

    // Sysop file-new private handlers deleted in PR-B step 23d:
    // SysopFileNew{Filename,Title,Artist,Year,Label,Catalog,Genre,
    // Url,Nfo}Screen own paint + dispatch + draft accumulation +
    // repo.insert + bus.notify + audit.
    // showSysopFileNewNfoProgress relocated to
    // SysopFileNewNfoScreen.paintProgress (private static).

        // Sysop broadcast private handlers deleted in PR-B step 23b:
    // SysopBroadcastScreen calls services.mentions().broadcastAll.

        // Forum private handlers + broadcasts deleted in PR-B step 20:
    // BasesList/ThreadsList/ThreadView/ComposeThread{Subject,Body}/
    // ComposePostBody screens own paint, key dispatch, and the
    // submit + bus.notify cascade. broadcastThreadView and
    // broadcastThreadsListUpdate are extinct — replaced by
    // ctx.publish(ThreadViewScreen.topicFor(tid)) /
    // ctx.publish(ThreadsListScreen.topicFor(bid)).

    // Presence info-view paint methods (showUserList /
    // showLastCallers / showWhosOnline) deleted in PR-B step 22.
    // InfoViewScreen now owns all three variants, branching on
    // VoidCoreSession.infoVariant in onEnter.

    // broadcastPresenceBanner moved to AuthFinaliser (post-PR-B
    // AuthFinaliser extraction). The leave-banner case still lives on
    // ScreenRouter.onDisconnect — it calls
    // bbsServices.authFinaliser().broadcastPresenceBanner(entry, false, ...).
    // The old phase-map iteration is gone; AuthFinaliser iterates
    // SessionRegistry.all() filtered by userId() != null (presence
    // and the auth pipeline guarantee userId is set iff the session
    // has passed login).

    // showGoodbye moved to GoodbyeScreen.onEnter (v1.4 PR-B).

    // handleLogin / authSucceeded moved to AuthFinaliser (post-PR-B
    // AuthFinaliser extraction). Login screens reach AuthFinaliser
    // via ctx.services().authFinaliser(); the typed-message
    // dispatchers (onAuthLogin / onAuthRegister) reach it via
    // bbsServices.authFinaliser(). The post-auth landing call back
    // into the router goes through the small PostAuthLanding
    // interface (AuthFinaliser holds a @Lazy reference) so the bean
    // graph stays acyclic at construction time.

    /**
     * Post-auth navigation. Priority:
     *   1. Deep-link intent (SPEC §4.6) — if the URL fragment maps to a
     *      known screen, land there.
     *   2. Persisted current_screen — for SPEC §13's "JVM restart
     *      mid-session → user re-rendered to current_screen" criterion,
     *      restore the screen the user was last on.
     *   3. Default to the main menu.
     *
     * The intent is consumed (cleared) so a later resume falls through to
     * current_screen / menu.
     */
    @Override
    public void applyPostAuth(VoidCoreSession session, UserRow user) {
        String intent = session.pendingIntent();
        session.setPendingIntent(null);
        if (intent != null && !intent.isBlank()) {
            if (resolveIntent(session, user, intent)) return;
            // Unrecognised intent — menu + notify per SPEC §4.6
            showMainMenu(session, user);
            send(session, Frames.notify("notifications",
                    "intent \"" + intent + "\" not recognised", "warn", 3000));
            return;
        }
        if (restoreFromCurrentScreen(session, user)) return;
        showMainMenu(session, user);
    }

    /**
     * Read {@code sessions.current_screen} and re-paint the screen the
     * user was on. Returns false (caller falls back to the menu) if the
     * screen JSON is missing, malformed, default-menu, or names a target
     * (bulletin id, file id) that no longer exists.
     */
    private boolean restoreFromCurrentScreen(VoidCoreSession session, UserRow user) {
        String token = session.sessionToken();
        if (token == null) return false;
        var s = sessions.peek(token).orElse(null);
        if (s == null || s.currentScreen() == null) return false;
        var cs = s.currentScreen();
        String kind = cs.path("kind").asText("");
        switch (kind) {
            case "bulletins_list" -> {
                resetStack(session, Phase.MENU);
                push(session, Phase.BULLETINS_LIST);
                return true;
            }
            case "bulletin" -> {
                long id = cs.path("id").asLong(-1);
                var doc = bbsServices.documents().byArticleIdOrLegacyBulletinId(id);
                if (doc.isEmpty()) return false;
                resetStack(session, Phase.MENU);
                push(session, Phase.BULLETINS_LIST);
                session.setCurrentBulletinId(doc.get().id());
                push(session, Phase.BULLETINS_VIEW);
                return true;
            }
            case "files_list", "releases_list" -> {
                resetStack(session, Phase.MENU);
                push(session, Phase.RELEASES_LIST);
                return true;
            }
            case "nfo", "release_nfo" -> {
                long id = cs.path("release_id").asLong(cs.path("file_id").asLong(-1));
                var doc = bbsServices.documents().byReleaseIdOrLegacyFileId(id);
                if (doc.isEmpty()) return false;
                resetStack(session, Phase.MENU);
                push(session, Phase.RELEASES_LIST);
                session.setCurrentReleaseId(doc.get().id());
                push(session, Phase.RELEASES_VIEW);
                return true;
            }
            case "document" -> {
                // v1.5 PR-3: restore-on-resume for the documents substrate.
                // Stack shape mirrors the bulletin/file deep-link paths —
                // [MENU, DOCUMENT_SCREEN] — so [Q] returns to menu.
                long id = cs.path("id").asLong(-1);
                var maybeDoc = bbsServices.documents().byId(id);
                if (maybeDoc.isEmpty()) return false;
                if (!bbsServices.documents().canRead(session, maybeDoc.get())) {
                    return false;
                }
                resetStack(session, Phase.MENU);
                session.setCurrentDocumentId(id);
                push(session, Phase.DOCUMENT_SCREEN);
                return true;
            }
            case "users" -> {
                resetStack(session, Phase.MENU);
                session.setInfoVariant(io.aeyer.voidcore.ws.flow.screen.impl.InfoViewScreen.VARIANT_USERS);
                push(session, Phase.INFO_VIEW);
                return true;
            }
            case "last_callers" -> {
                resetStack(session, Phase.MENU);
                session.setInfoVariant(io.aeyer.voidcore.ws.flow.screen.impl.InfoViewScreen.VARIANT_LAST_CALLERS);
                push(session, Phase.INFO_VIEW);
                return true;
            }
            case "whos_online" -> {
                resetStack(session, Phase.MENU);
                session.setInfoVariant(io.aeyer.voidcore.ws.flow.screen.impl.InfoViewScreen.VARIANT_WHOS_ONLINE);
                push(session, Phase.INFO_VIEW);
                return true;
            }
            case "oneliners" -> {
                resetStack(session, Phase.MENU);
                push(session, Phase.ONELINERS);
                return true;
            }
            case "doors_menu" -> {
                resetStack(session, Phase.MENU);
                push(session, Phase.DOORS_MENU);
                return true;
            }
            case "door_session" -> {
                String doorId = cs.path("door_id").asText(null);
                if (doorId == null || doorId.isBlank()) return false;
                resetStack(session, Phase.MENU);
                push(session, Phase.DOORS_MENU);
                session.setCurrentDoorId(doorId);
                push(session, Phase.DOOR_SESSION);
                return true;
            }
            case "chat", "chat_rooms" -> {
                resetStack(session, Phase.MENU);
                push(session, Phase.CHAT);
                return true;
            }
            case "chat_directs" -> {
                resetStack(session, Phase.MENU);
                push(session, Phase.CHAT);
                push(session, Phase.CHAT_DIRECTS);
                return true;
            }
            case "chat_direct_new" -> {
                resetStack(session, Phase.MENU);
                push(session, Phase.CHAT);
                push(session, Phase.CHAT_DIRECTS);
                push(session, Phase.CHAT_DIRECT_NEW);
                return true;
            }
            case "chat_room" -> {
                String room = cs.path("room").asText(null);
                if (room == null || room.isBlank()) return false;
                resetStack(session, Phase.MENU);
                push(session, Phase.CHAT);
                session.setCurrentChatRoomSlug(room);
                push(session, Phase.CHAT_ROOM);
                return true;
            }
            case "netmail_inbox" -> {
                resetStack(session, Phase.MENU);
                push(session, Phase.NETMAIL_INBOX);
                return true;
            }
            case "netmail_outbox" -> {
                resetStack(session, Phase.MENU);
                push(session, Phase.NETMAIL_OUTBOX);
                return true;
            }
            case "sysop_menu" -> {
                resetStack(session, Phase.MENU);
                push(session, Phase.SYSOP_MENU);
                return true;
            }
            case "sysop_screen_toggles" -> {
                resetStack(session, Phase.MENU);
                push(session, Phase.SYSOP_MENU);
                push(session, Phase.SYSOP_SCREEN_TOGGLES);
                return true;
            }
            case "sysop_users" -> {
                resetStack(session, Phase.MENU);
                push(session, Phase.SYSOP_MENU);
                push(session, Phase.SYSOP_USERS);
                return true;
            }
            case "sysop_bulletins" -> {
                resetStack(session, Phase.MENU);
                push(session, Phase.SYSOP_MENU);
                push(session, Phase.SYSOP_BULLETINS);
                return true;
            }
            case "sysop_roles" -> {
                resetStack(session, Phase.MENU);
                push(session, Phase.SYSOP_MENU);
                push(session, Phase.SYSOP_ROLES);
                return true;
            }
            case "sysop_chat_rooms" -> {
                resetStack(session, Phase.MENU);
                push(session, Phase.SYSOP_MENU);
                push(session, Phase.SYSOP_CHAT_ROOMS);
                return true;
            }
            case "sysop_files", "sysop_releases" -> {
                resetStack(session, Phase.MENU);
                push(session, Phase.SYSOP_MENU);
                push(session, Phase.SYSOP_RELEASES);
                return true;
            }
            case "bases" -> {
                resetStack(session, Phase.MENU);
                push(session, Phase.BASES_LIST);
                return true;
            }
            case "threads" -> {
                long bid = cs.path("base_id").asLong(-1);
                if (bid < 0 || messageBases.findById(bid).isEmpty()) return false;
                resetStack(session, Phase.MENU);
                push(session, Phase.BASES_LIST);
                session.setSelectedBaseId(bid);
                push(session, Phase.THREADS_LIST);
                return true;
            }
            case "thread" -> {
                long tid = cs.path("id").asLong(-1);
                var t = threads.findById(tid).orElse(null);
                if (t == null) return false;
                resetStack(session, Phase.MENU);
                push(session, Phase.BASES_LIST);
                session.setSelectedBaseId(t.baseId());
                push(session, Phase.THREADS_LIST);
                session.setSelectedThreadId(tid);
                push(session, Phase.THREAD_VIEW);
                return true;
            }
            case "netmail_read" -> {
                long id = cs.path("id").asLong(-1);
                Long uid = session.userId();
                if (uid == null) return false;
                if (netmail.findOwned(id, uid).isEmpty()) return false;
                resetStack(session, Phase.MENU);
                push(session, Phase.NETMAIL_INBOX);
                session.setCurrentNetmailId(id);
                push(session, Phase.NETMAIL_READ);
                return true;
            }
            case "custom_screen" -> {
                String screenName = cs.path("screen").asText(null);
                if (screenName == null || screenName.isBlank()) return false;
                resetStack(session, Phase.MENU);
                return pushRoute(session, ScreenRoute.custom(screenName));
            }
            // "menu" or anything else: caller falls through to showMainMenu
            default -> { return false; }
        }
    }

    /**
     * v1 deep-link grammar per SPEC §4.6:
     *   nfo/&lt;filename-or-slug&gt;   bulletin/&lt;id&gt;   chat
     *   user/&lt;handle&gt;             thread/&lt;id&gt;
     *
     * Returns true if the intent was recognised AND landed (or recognised
     * AND deferred to a "not implemented" notice). False means "unknown
     * grammar" — caller falls back to the menu + intent-not-recognised
     * notify.
     */
    private boolean resolveIntent(VoidCoreSession session, UserRow user, String intent) {
        if (intent.startsWith("nfo/")) {
            String key = intent.substring(4);
            var maybe = bbsServices.documents().findReleaseByFilenameOrSlug(key);
            if (maybe.isEmpty()) return false; // valid grammar, no match
            resetStack(session, Phase.MENU);
            push(session, Phase.RELEASES_LIST);
            session.setCurrentReleaseId(maybe.get().id());
            push(session, Phase.RELEASES_VIEW);
            return true;
        }
        if (intent.startsWith("bulletin/")) {
            String idStr = intent.substring("bulletin/".length());
            try {
                long id = Long.parseLong(idStr);
                var doc = bbsServices.documents().byArticleIdOrLegacyBulletinId(id);
                if (doc.isEmpty()) return false;
                resetStack(session, Phase.MENU);
                push(session, Phase.BULLETINS_LIST);
                session.setCurrentBulletinId(doc.get().id());
                push(session, Phase.BULLETINS_VIEW);
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        if (intent.equals("chat")) {
            resetStack(session, Phase.MENU);
            push(session, Phase.CHAT);
            return true;
        }
        if (intent.startsWith("doc/")) {
            // v1.5 PR-3: documents-substrate deep link. Resolves the slug
            // via DocumentView (cache-aware), gates on canRead so the
            // safe-failure rule applies to private docs (not-readable
            // looks the same as not-found — no leak).
            String slug = intent.substring("doc/".length());
            var maybe = bbsServices.documents().bySlug(slug);
            if (maybe.isEmpty()) return false;
            var doc = maybe.get();
            if (!bbsServices.documents().canRead(session, doc)) return false;
            resetStack(session, Phase.MENU);
            session.setCurrentDocumentId(doc.id());
            push(session, Phase.DOCUMENT_SCREEN);
            return true;
        }
        if (intent.startsWith("thread/")) {
            String idStr = intent.substring("thread/".length());
            try {
                long id = Long.parseLong(idStr);
                var t = threads.findById(id).orElse(null);
                if (t == null) return false;
                resetStack(session, Phase.MENU);
                push(session, Phase.BASES_LIST);
                session.setSelectedBaseId(t.baseId());
                push(session, Phase.THREADS_LIST);
                session.setSelectedThreadId(id);
                push(session, Phase.THREAD_VIEW);
                return true;
            } catch (NumberFormatException e) { return false; }
        }
        if (intent.startsWith("user/")) {
            // PR-8: user profile is the documents faceted surface
            // filtered by that user's authored docs. Per ADR-023 +
            // SPEC-documents §4 — there's no separate profile screen
            // primitive; a user IS the set of their authored
            // documents. The handle resolves to user id; we set the
            // by= filter and push DOCS_RESULTS. If the handle doesn't
            // resolve, fall back to menu + notify.
            String handle = intent.substring("user/".length());
            var maybeUser = users.findByHandle(handle);
            if (maybeUser.isEmpty()) {
                showMainMenu(session, user);
                send(session, Frames.notify("notifications",
                        "user not found: " + handle, "warn", 3000));
                return true;
            }
            UserRow target = maybeUser.get();
            io.aeyer.voidcore.documents.DocumentFilter filter =
                    io.aeyer.voidcore.documents.DocumentFilter.empty()
                            .withAuthor(target.id());
            session.setDocsFilter(filter.serialise());
            session.setDocsResultsPage(0);
            session.setDocsResultsSort(null); // default RECENT
            resetStack(session, Phase.MENU);
            push(session, Phase.DOCS_RESULTS);
            return true;
        }
        return false;
    }

    private void attachUser(VoidCoreSession session, UserRow user) {
        session.setUserId(user.id());
        session.setHandle(user.handle());
        // Mirror AuthFinaliser: stamp sysop-ness on the session so
        // canRead-style funnels can answer it without a UserRepository
        // hop. Resume path runs through here too.
        session.setSysop(user.isSysop());
        // Auth-success: drop the transient login-handle scratch state.
        // It served the password-prompt step; the authoritative handle
        // is now on session.handle().
        session.setPendingLoginHandle(null);
    }

    // persistCurrentScreen deleted in PR-B step 24 — screens call
    // ctx.persistCurrentScreen(...) which goes through
    // BbsServices.persistCurrentScreen (same logic, lives next to
    // the other cross-cutting helpers).

    // --- Send helpers ---------------------------------------------------

    private void send(VoidCoreSession session, ServerMessage m) {
        try {
            session.send(m);
        } catch (IOException e) {
            // WARN: an outbound message failed to write — the user is
            // missing UI updates. Not catastrophic (session usually
            // closes shortly after), but the operator should see it.
            log.warn("send failed for session={}: {}", session.id(), e.toString());
        }
    }

    /**
     * Send a response message that echoes the inbound id for SPEC §4
     * request/response correlation. Use for resume.ok / resume.err and
     * other response-shaped messages.
     */
    private void sendReply(VoidCoreSession session, ServerMessage m, String inReplyTo) {
        try {
            session.sendInReplyTo(m, inReplyTo);
        } catch (IOException e) {
            log.warn("sendReply failed for session={}: {}", session.id(), e.toString());
        }
    }

    private void sendError(VoidCoreSession session, AuthService.Failure f) {
        sendError(session, f, null);
    }

    /**
     * Send an auth.err that echoes the triggering inbound's id (e.g.
     * the client's auth.register message id when register fails).
     */
    private void sendError(VoidCoreSession session, AuthService.Failure f, String inReplyTo) {
        sendReply(session, AuthService.toAuthErr(f), inReplyTo);
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

    // --- State ----------------------------------------------------------

    // Phase enum extracted to io.aeyer.voidcore.ws.flow.screen.Phase as part of
    // the v1.4 refactor (ADR-025 / SPEC-screens.md). Imported above.

    // SessionState record collapsed in PR-B step 24 — phases is now
    // Map<String, Phase> directly. ADR-029 step 4 done.
}
