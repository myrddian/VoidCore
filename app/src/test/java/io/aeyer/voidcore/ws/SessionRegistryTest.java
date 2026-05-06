package io.aeyer.voidcore.ws;

import io.aeyer.voidcore.ws.protocol.ProtocolTypeRegistry;
import io.aeyer.voidcore.ws.session.SessionProxy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests {@link SessionRegistry} lifecycle behaviour for ADR-033 phase 1.
 * Covers the four moving parts the design depends on:
 *
 * <ul>
 *   <li>register / detach for anon (no token) sessions — actor terminates
 *       immediately, no TTL grace.</li>
 *   <li>register / rekeyOnAuth / detach for post-auth sessions — actor
 *       moves to the detached pool with a timestamp.</li>
 *   <li>attachExistingByToken — swaps a fresh WS into a detached actor
 *       and yanks it back to ATTACHED; tears down the surplus anon
 *       proxy that was created for the new WS.</li>
 *   <li>sweep — TTL-expired detached actors are terminated.</li>
 *   <li>termination listeners — fire on every permanent shutdown
 *       (anon disconnect, TTL expiry, explicit close, rekey eviction).</li>
 * </ul>
 *
 * <p>Spring is not booted; the registry is constructed directly with a
 * very short TTL so sweep tests can run in real time without flakiness.
 */
class SessionRegistryTest {

    private SessionRegistry registry;
    private List<String> terminations;
    private final AtomicInteger wsIdCounter = new AtomicInteger(1);

    @BeforeEach
    void setUp() {
        // 1-second TTL so sweep tests are fast.
        registry = new SessionRegistry(new ObjectMapper(), mock(ProtocolTypeRegistry.class), 1);
        terminations = new ArrayList<>();
        registry.addTerminationListener(p -> terminations.add(p.id()));
    }

    @AfterEach
    void tearDown() {
        // Make sure no virtual threads leak between tests by terminating
        // anything still active or detached.
        for (VoidCoreSession s : new ArrayList<>(registry.all())) {
            registry.close(s.id());
        }
        // Force a sweep with a generous cutoff to clear detached.
        try { Thread.sleep(1100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        registry.sweep();
    }

    private WebSocketSession mockWs() {
        WebSocketSession ws = mock(WebSocketSession.class);
        String id = "ws-" + wsIdCounter.getAndIncrement();
        when(ws.getId()).thenReturn(id);
        when(ws.isOpen()).thenReturn(true);
        return ws;
    }

    @Test
    void registerPlacesSessionInActiveAndAliasMaps() {
        WebSocketSession ws = mockWs();
        VoidCoreSession s = registry.register(ws);
        assertThat(s).isNotNull();
        assertThat(registry.size()).isEqualTo(1);
        assertThat(registry.detachedSize()).isZero();
        assertThat(registry.get(ws.getId())).isSameAs(s);
        assertThat(registry.all()).containsExactly(s);
    }

    @Test
    void detachAnonTerminatesImmediately() {
        WebSocketSession ws = mockWs();
        VoidCoreSession s = registry.register(ws);
        SessionRegistry.DetachOutcome outcome = registry.detach(ws.getId());
        assertThat(outcome).isEqualTo(SessionRegistry.DetachOutcome.ANON_TERMINATED);
        assertThat(registry.size()).isZero();
        assertThat(registry.detachedSize()).isZero();
        assertThat(terminations).containsExactly(s.id());
        // The wsId alias is gone.
        assertThat(registry.get(ws.getId())).isNull();
    }

    @Test
    void detachPostAuthMovesToDetachedPoolWithoutTerminating() {
        WebSocketSession ws = mockWs();
        VoidCoreSession s = registry.register(ws);
        // Set a token on the proxy directly via the actor — emulates
        // what AuthFinaliser.onSuccess does when login lands.
        s.setSessionToken("tok-abc-123");
        registry.rekeyOnAuth(s.id(), "tok-abc-123");

        SessionRegistry.DetachOutcome outcome = registry.detach(ws.getId());
        assertThat(outcome).isEqualTo(SessionRegistry.DetachOutcome.DETACHED);
        assertThat(registry.size()).isZero();
        assertThat(registry.detachedSize()).isEqualTo(1);
        // No termination yet — actor is preserved.
        assertThat(terminations).isEmpty();
        // get() by old wsId returns null (alias removed on detach).
        assertThat(registry.get(ws.getId())).isNull();
        // getByToken also returns empty (only ATTACHED matches).
        assertThat(registry.getByToken("tok-abc-123")).isEmpty();
    }

    @Test
    void attachExistingByTokenSwapsWsAndTerminatesAnon() {
        // Stage 1: existing post-auth session disconnects.
        WebSocketSession ws1 = mockWs();
        VoidCoreSession s1 = registry.register(ws1);
        s1.setSessionToken("tok-xyz");
        registry.rekeyOnAuth(s1.id(), "tok-xyz");
        registry.detach(ws1.getId());
        assertThat(registry.detachedSize()).isEqualTo(1);
        assertThat(terminations).isEmpty();

        // Stage 2: client reconnects on a fresh WS, sending AuthResume.
        WebSocketSession ws2 = mockWs();
        VoidCoreSession anon = registry.register(ws2);
        assertThat(anon.id()).isNotEqualTo(s1.id());

        var maybeExisting = registry.attachExistingByToken("tok-xyz", ws2);
        assertThat(maybeExisting).isPresent();
        VoidCoreSession existing = maybeExisting.get();
        // Same actor as s1 — the original lives on.
        assertThat(existing.id()).isEqualTo(s1.id());
        // The anon actor was torn down by the swap.
        assertThat(terminations).containsExactly(anon.id());
        // Active is now the existing actor; detached is empty.
        assertThat(registry.size()).isEqualTo(1);
        assertThat(registry.detachedSize()).isZero();
        // Lookup by ws2.getId() resolves to the existing actor.
        assertThat(registry.get(ws2.getId())).isSameAs(existing);
    }

    @Test
    void attachExistingByTokenReturnsEmptyIfNoDetachedActor() {
        WebSocketSession ws = mockWs();
        var result = registry.attachExistingByToken("nonexistent-token", ws);
        assertThat(result).isEmpty();
    }

    @Test
    void sweepTerminatesExpiredDetachedActors() throws InterruptedException {
        WebSocketSession ws = mockWs();
        VoidCoreSession s = registry.register(ws);
        s.setSessionToken("tok-sweep");
        registry.rekeyOnAuth(s.id(), "tok-sweep");
        registry.detach(ws.getId());
        assertThat(registry.detachedSize()).isEqualTo(1);

        // Wait past the 1-second TTL.
        Thread.sleep(1100);

        registry.sweep();
        assertThat(registry.detachedSize()).isZero();
        assertThat(terminations).containsExactly(s.id());
    }

    @Test
    void sweepLeavesFreshDetachedActorsAlone() throws InterruptedException {
        WebSocketSession ws = mockWs();
        VoidCoreSession s = registry.register(ws);
        s.setSessionToken("tok-fresh");
        registry.rekeyOnAuth(s.id(), "tok-fresh");
        registry.detach(ws.getId());

        // Sweep immediately — entry is fresh, well within TTL.
        registry.sweep();
        assertThat(registry.detachedSize()).isEqualTo(1);
        assertThat(terminations).isEmpty();

        // Re-attach within the TTL works.
        WebSocketSession ws2 = mockWs();
        registry.register(ws2);
        var existing = registry.attachExistingByToken("tok-fresh", ws2);
        assertThat(existing).isPresent();
        assertThat(existing.get().id()).isEqualTo(s.id());
    }

    @Test
    void rekeyOnAuthEvictsPreviousActorHoldingSameToken() {
        // First session takes the token.
        WebSocketSession ws1 = mockWs();
        VoidCoreSession s1 = registry.register(ws1);
        s1.setSessionToken("tok-shared");
        registry.rekeyOnAuth(s1.id(), "tok-shared");
        terminations.clear();

        // Second session claims the same token (e.g. fresh login on
        // another device). The previous one must be evicted to avoid
        // a leak.
        WebSocketSession ws2 = mockWs();
        VoidCoreSession s2 = registry.register(ws2);
        s2.setSessionToken("tok-shared");
        registry.rekeyOnAuth(s2.id(), "tok-shared");

        // s1 was evicted (terminated).
        assertThat(terminations).containsExactly(s1.id());
        // s2 holds the token and is still active.
        assertThat(registry.size()).isEqualTo(1);
        assertThat(registry.get(ws2.getId())).isSameAs(s2);
    }

    @Test
    void closeTerminatesActiveActorImmediately() {
        WebSocketSession ws = mockWs();
        VoidCoreSession s = registry.register(ws);

        registry.close(s.id());
        assertThat(registry.size()).isZero();
        assertThat(terminations).containsExactly(s.id());
        assertThat(registry.get(ws.getId())).isNull();
    }

    @Test
    void closeTerminatesDetachedActorImmediately() {
        WebSocketSession ws = mockWs();
        VoidCoreSession s = registry.register(ws);
        s.setSessionToken("tok-close");
        registry.rekeyOnAuth(s.id(), "tok-close");
        registry.detach(ws.getId());
        assertThat(registry.detachedSize()).isEqualTo(1);
        assertThat(terminations).isEmpty();

        registry.close(s.id());
        assertThat(registry.detachedSize()).isZero();
        assertThat(terminations).containsExactly(s.id());
    }

    @Test
    void getReturnsNullForUnknownWsId() {
        assertThat(registry.get("never-seen")).isNull();
    }

    @Test
    void detachUnknownWsIdIsHarmless() {
        SessionRegistry.DetachOutcome outcome = registry.detach("never-registered");
        assertThat(outcome).isEqualTo(SessionRegistry.DetachOutcome.UNKNOWN);
        assertThat(terminations).isEmpty();
    }
}
