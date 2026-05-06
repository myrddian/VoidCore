package io.aeyer.voidcore.ws;

import io.aeyer.voidcore.ws.protocol.ProtocolTypeRegistry;
import io.aeyer.voidcore.ws.session.SessionActor;
import io.aeyer.voidcore.ws.session.SessionProxy;
import io.aeyer.voidcore.ws.session.SessionCore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Token-keyed session registry implementing ADR-033. The actor outlives
 * its WebSocket: a session is in one of two states —
 *
 * <ul>
 *   <li><strong>ATTACHED</strong> — the actor has a live underlying WS.
 *       Inbound dispatch resolves {@code wsId → actorId} via
 *       {@link #wsIdToActorId} and the proxy is in {@link #active}.</li>
 *   <li><strong>DETACHED</strong> — the WS closed but the actor is kept
 *       alive in {@link #detached} for a TTL window so the client can
 *       reconnect with {@code AuthResume(token)} and resume in place
 *       (in-flight composes / breadcrumbs / phase stack all survive).
 *       After the TTL fires {@link #sweep} shuts the actor down and
 *       fires the cleanup callback.</li>
 * </ul>
 *
 * <h2>Identity</h2>
 *
 * <p>The actor's id ({@code proxy.id()}, {@code core.id()}) is stable —
 * captured at construction from the initial WS's getId(). The current
 * underlying WS may change via {@link #attachExistingByToken}; the actor
 * id never does.
 *
 * <h2>Lookup paths</h2>
 *
 * <ul>
 *   <li>{@link #get} — lookup by current WS id (the inbound-dispatch
 *       fast path). Only ATTACHED sessions match.</li>
 *   <li>{@link #getByToken} — lookup by session token. Both ATTACHED
 *       and DETACHED sessions match — used by the AuthResume reconnect
 *       path to find a still-alive detached actor.</li>
 *   <li>{@link #all} — iterate ATTACHED sessions only. Used by
 *       heartbeat ticks, presence broadcasts, mention delivery — none
 *       of which are meaningful for a session whose WS is gone.</li>
 * </ul>
 *
 * <h2>Termination callback</h2>
 *
 * <p>When an actor shuts down for real (anon disconnect, TTL expiry,
 * explicit logout, replaced) the registry calls every registered
 * {@link #addTerminationListener termination listener} with the proxy.
 * That's how {@code ScreenRouter} cleans up phases / nav state / bus
 * subscriptions / presence — without the registry having to know about
 * any of those collaborators.
 */
@Component
public class SessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(SessionRegistry.class);

    /** Primary map: actor id → proxy. ATTACHED sessions only. */
    private final ConcurrentHashMap<String, SessionProxy> active = new ConcurrentHashMap<>();

    /** Alias: current WS id → actor id. ATTACHED only. */
    private final ConcurrentHashMap<String, String> wsIdToActorId = new ConcurrentHashMap<>();

    /**
     * Alias: session token → actor id. Set by {@link #rekeyOnAuth} on
     * auth success / resume. Survives {@link #detach} so the AuthResume
     * reconnect path can find the right actor.
     */
    private final ConcurrentHashMap<String, String> tokenToActorId = new ConcurrentHashMap<>();

    /** Detached pool: actor id → entry (proxy, token, detachedAt). */
    private final ConcurrentHashMap<String, DetachedEntry> detached = new ConcurrentHashMap<>();

    private final CopyOnWriteArrayList<Consumer<SessionProxy>> terminationListeners =
            new CopyOnWriteArrayList<>();

    private final ObjectMapper json;
    private final ProtocolTypeRegistry types;
    private final Duration detachTtl;

    public SessionRegistry(ObjectMapper json,
                           ProtocolTypeRegistry types,
                           @Value("${voidcore.ws.session-detach-ttl-seconds:60}") int detachTtlSeconds) {
        this.json = json;
        this.types = types;
        this.detachTtl = Duration.ofSeconds(detachTtlSeconds);
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────

    /**
     * Create a fresh anonymous (pre-auth) ATTACHED session for a new
     * WebSocket. Promoted to token-keyed by {@link #rekeyOnAuth} when
     * auth succeeds.
     */
    VoidCoreSession register(WebSocketSession underlying) {
        SessionCore core = new SessionCore(underlying, json, types);
        SessionActor actor = new SessionActor(core);
        SessionProxy proxy = new SessionProxy(actor, core.id(), core.connectedAt());

        SessionProxy prev = active.put(proxy.id(), proxy);
        if (prev != null) {
            // Different WS = different id, so this should never happen.
            // If it does, an actor is already running with our actor id —
            // shut it down to avoid leaking the virtual thread.
            log.warn("[ws-trace] registry-add id={} replaced existing proxy; shutting old actor",
                    proxy.id());
            terminate(prev, "id-collision");
        }
        wsIdToActorId.put(underlying.getId(), proxy.id());
        log.debug("[ws-trace] registry-add actorId={} wsId={} active={} detached={}",
                proxy.id(), underlying.getId(), active.size(), detached.size());
        return proxy;
    }

    /**
     * Promote an anonymous session to token-keyed. Called by
     * {@code AuthFinaliser} after auth.login / auth.register / auth.resume
     * succeeds. The token-actor mapping survives {@link #detach} so the
     * client can reconnect with the same token and find the still-alive
     * actor.
     *
     * <p>If a different actor is already mapped to this token (multi-
     * device scenario, or a reconnect-without-attach race), the existing
     * one is terminated — the new auth replaces it. That's the
     * "auth.login from a different browser invalidates the old session"
     * behaviour the auth-side already applies at the DB level.
     */
    public void rekeyOnAuth(String actorId, String token) {
        if (token == null || token.isBlank()) {
            log.warn("[ws-trace] registry-rekey-skip actorId={} reason=blank-token", actorId);
            return;
        }
        String previousActorId = tokenToActorId.put(token, actorId);
        if (previousActorId != null && !previousActorId.equals(actorId)) {
            // A different actor was holding this token. Tear it down so
            // we don't leak.
            log.info("[ws-trace] registry-rekey-evicts-previous actorId={} previousActorId={} token-prefix={}",
                    actorId, previousActorId, tokenPrefix(token));
            evict(previousActorId, "rekey-replaced");
        }
        log.debug("[ws-trace] registry-rekey actorId={} token-prefix={}",
                actorId, tokenPrefix(token));
    }

    /**
     * The WebSocket has closed. If the session is anonymous (no token),
     * terminate immediately — there's nothing to preserve. If the
     * session has a token, move it to the detached pool with the
     * current timestamp; the TTL sweeper will GC it if no
     * {@link #attachExistingByToken} arrives before then.
     */
    public DetachOutcome detach(String wsId) {
        String actorId = wsIdToActorId.remove(wsId);
        if (actorId == null) {
            log.debug("[ws-trace] registry-detach-unknown wsId={}", wsId);
            return DetachOutcome.UNKNOWN;
        }
        SessionProxy proxy = active.remove(actorId);
        if (proxy == null) {
            log.debug("[ws-trace] registry-detach-not-active actorId={} wsId={}", actorId, wsId);
            return DetachOutcome.UNKNOWN;
        }
        String token = proxy.sessionToken();
        if (token == null || token.isBlank()) {
            // Anon — no rejoin path possible. Terminate now.
            log.debug("[ws-trace] registry-detach-anon-terminate actorId={} wsId={}", actorId, wsId);
            terminate(proxy, "anon-disconnect");
            return DetachOutcome.ANON_TERMINATED;
        }
        // Post-auth — preserve the actor in the detached pool.
        detached.put(actorId, new DetachedEntry(proxy, token, Instant.now()));
        log.info("[ws-trace] registry-detach actorId={} wsId={} token-prefix={} detached-pool-size={} reap-after-seconds={}",
                actorId, wsId, tokenPrefix(token), detached.size(), detachTtl.getSeconds());
        return DetachOutcome.DETACHED;
    }

    /**
     * Reconnect path: a new WebSocket arrives carrying a token. If a
     * detached actor exists for that token, swap the new WS into it,
     * move it back to ATTACHED, and return the proxy. Otherwise
     * return empty — the caller should fall through to the normal
     * resume path (which will hit the DB to validate the token, then
     * either land a fresh actor in {@link #register} or reject).
     *
     * <p>Caller responsibility: this method swaps the WS but does NOT
     * dispose of any anonymous actor that {@link #register} may have
     * just created for the same WS — that's a separate decision in the
     * dispatch path. (In practice the handler creates an anon actor in
     * {@code afterConnectionEstablished}, then if AuthResume arrives
     * with a token whose detached actor exists, it terminates the anon
     * one and swaps to the existing one.)
     */
    public Optional<VoidCoreSession> attachExistingByToken(String token, WebSocketSession newUnderlying) {
        if (token == null || token.isBlank()) return Optional.empty();
        String existingActorId = tokenToActorId.get(token);
        if (existingActorId == null) return Optional.empty();
        DetachedEntry entry = detached.remove(existingActorId);
        if (entry == null) {
            // Token is mapped, but the actor isn't detached — either
            // it's currently ATTACHED (another tab using the same token)
            // or it was already GC'd. Either way, nothing to attach to.
            log.debug("[ws-trace] registry-attach-by-token-miss token-prefix={} actorId={} active-has={}",
                    tokenPrefix(token), existingActorId, active.containsKey(existingActorId));
            return Optional.empty();
        }

        // The new WS was already bound to a fresh anonymous actor by
        // afterConnectionEstablished → register. That anon is now
        // surplus (we're going to swap into the existing token-keyed
        // actor instead). Find it and terminate it before we move the
        // wsId alias over.
        String anonActorId = wsIdToActorId.get(newUnderlying.getId());
        if (anonActorId != null && !anonActorId.equals(existingActorId)) {
            SessionProxy anon = active.remove(anonActorId);
            if (anon != null) {
                wsIdToActorId.remove(newUnderlying.getId());
                terminate(anon, "swapped-out-for-existing");
            }
        }

        // Swap the new WS into the existing actor's core. This goes
        // through the actor's queue so it's serialised against any
        // in-flight message processing.
        SessionProxy proxy = entry.proxy();
        proxy.actor().submit(c -> { c.swapUnderlying(newUnderlying); return null; });
        active.put(existingActorId, proxy);
        wsIdToActorId.put(newUnderlying.getId(), existingActorId);
        long heldFor = Duration.between(entry.detachedAt(), Instant.now()).toMillis();
        log.info("[ws-trace] registry-attach-existing actorId={} wsId={} token-prefix={} held-detached-ms={}",
                existingActorId, newUnderlying.getId(), tokenPrefix(token), heldFor);
        return Optional.of(proxy);
    }

    /**
     * Explicit shutdown — used by logout. Terminates the actor
     * immediately (no TTL grace) and removes all references.
     */
    public void close(String actorId) {
        SessionProxy proxy = active.remove(actorId);
        if (proxy != null) {
            // Was ATTACHED — also clean the WS alias.
            wsIdToActorId.values().removeIf(actorId::equals);
        } else {
            DetachedEntry entry = detached.remove(actorId);
            if (entry != null) proxy = entry.proxy();
        }
        if (proxy == null) {
            log.debug("[ws-trace] registry-close-unknown actorId={}", actorId);
            return;
        }
        terminate(proxy, "explicit-close");
    }

    /**
     * Permanent shutdown of an actor that's already been pulled from
     * the active/detached maps. Drops the token alias, fires
     * termination listeners, shuts down the actor's virtual thread.
     */
    private void terminate(SessionProxy proxy, String reason) {
        String token = proxy.sessionToken();
        // Best-effort: tell the client what just happened. If the WS is
        // already gone (the common case for ttl-expired) this is a no-op.
        // Keeps the actor alive long enough to push the message through
        // its own queue before shutdown().
        try {
            proxy.send(new io.aeyer.voidcore.ws.protocol.ServerMessage.ResumeErr(
                    "AUTH_REQUIRED", "session " + reason));
        } catch (java.io.IOException e) {
            log.debug("[ws-trace] registry-terminate-notify-fail actorId={} reason={} ex={}",
                    proxy.id(), reason, e.toString());
        }
        if (token != null) tokenToActorId.remove(token, proxy.id());
        for (Consumer<SessionProxy> listener : terminationListeners) {
            try {
                listener.accept(proxy);
            } catch (RuntimeException e) {
                log.warn("[ws-trace] registry-terminate-listener-failed actorId={} reason={} ex={}",
                        proxy.id(), reason, e.toString());
            }
        }
        proxy.actor().shutdown();
        log.info("[ws-trace] registry-terminate actorId={} reason={} active={} detached={}",
                proxy.id(), reason, active.size(), detached.size());
    }

    /**
     * Used by {@link #rekeyOnAuth} when a different actor was holding
     * a token: yank it from wherever it lives and terminate.
     */
    private void evict(String actorId, String reason) {
        SessionProxy proxy = active.remove(actorId);
        if (proxy != null) {
            wsIdToActorId.values().removeIf(actorId::equals);
        } else {
            DetachedEntry e = detached.remove(actorId);
            if (e != null) proxy = e.proxy();
        }
        if (proxy != null) terminate(proxy, reason);
    }

    // ─── TTL sweeper ──────────────────────────────────────────────────────

    /**
     * Scheduled sweep of the detached pool. Any entry older than the
     * configured TTL is shut down. Runs every 10 seconds — short
     * enough that the worst-case lifetime of an expired actor is
     * {@code TTL + 10s}, long enough that the sweeper itself is
     * negligible cost.
     */
    @Scheduled(fixedDelayString = "${voidcore.ws.session-sweep-seconds:10}",
            timeUnit = java.util.concurrent.TimeUnit.SECONDS)
    public void sweep() {
        if (detached.isEmpty()) return;
        Instant cutoff = Instant.now().minus(detachTtl);
        var expired = detached.entrySet().stream()
                .filter(e -> e.getValue().detachedAt().isBefore(cutoff))
                .map(java.util.Map.Entry::getKey)
                .collect(Collectors.toList());
        if (expired.isEmpty()) {
            log.debug("[ws-trace] registry-sweep detached={} (none expired)", detached.size());
            return;
        }
        for (String actorId : expired) {
            DetachedEntry entry = detached.remove(actorId);
            if (entry == null) continue;
            log.info("[ws-trace] registry-sweep-expired actorId={} held-ms={}",
                    actorId, Duration.between(entry.detachedAt(), Instant.now()).toMillis());
            terminate(entry.proxy(), "ttl-expired");
        }
    }

    // ─── Lookup ───────────────────────────────────────────────────────────

    /**
     * Inbound-dispatch lookup: WS id → proxy. Uses the alias map so
     * the lookup works after a WS swap (the actor's stable id is
     * different from the current WS id post-swap).
     */
    public VoidCoreSession get(String wsId) {
        String actorId = wsIdToActorId.get(wsId);
        if (actorId == null) {
            // Fall back to direct actor-id lookup. Pre-swap,
            // wsId == actorId so this branch is mostly redundant; kept
            // as a fast path for callers that already hold the
            // actor id (e.g. ScreenRouter session.id() lookups).
            return active.get(wsId);
        }
        return active.get(actorId);
    }

    /**
     * Token-keyed lookup. Returns ATTACHED matches only — the detached
     * pool is reachable solely through {@link #attachExistingByToken}
     * (which moves the actor out of the pool atomically).
     */
    public Optional<VoidCoreSession> getByToken(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        String actorId = tokenToActorId.get(token);
        if (actorId == null) return Optional.empty();
        return Optional.ofNullable(active.get(actorId));
    }

    /** Iterate ATTACHED sessions only (heartbeat, presence, mentions). */
    public Collection<VoidCoreSession> all() {
        return Collections.unmodifiableCollection(active.values());
    }

    public int size() {
        return active.size();
    }

    public int detachedSize() {
        return detached.size();
    }

    // ─── Termination listeners ────────────────────────────────────────────

    /**
     * Register a callback to be invoked when an actor is permanently
     * shut down (anon disconnect, TTL expiry, explicit close, replaced
     * by rekey). Listeners are called inside the registry — they should
     * be lightweight; long work belongs on the actor's queue.
     */
    public void addTerminationListener(Consumer<SessionProxy> listener) {
        terminationListeners.add(listener);
    }

    // ─── Types ────────────────────────────────────────────────────────────

    public enum DetachOutcome {
        /** Anonymous session — actor was terminated immediately. */
        ANON_TERMINATED,
        /** Post-auth session — actor moved to detached pool, TTL armed. */
        DETACHED,
        /** wsId wasn't known — already detached, never registered, etc. */
        UNKNOWN
    }

    private record DetachedEntry(SessionProxy proxy, String token, Instant detachedAt) {}

    private static String tokenPrefix(String token) {
        if (token == null || token.length() < 8) return "***";
        return token.substring(0, 8) + "...";
    }
}
