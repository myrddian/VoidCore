package io.aeyer.voidcore.ws.session;

import io.aeyer.voidcore.ws.protocol.ProtocolTypeRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SessionActor}. Exercises the queue + worker
 * mechanics directly against a {@link SessionCore} backed by a
 * mocked {@link WebSocketSession} (no Spring, no real WS).
 *
 * <p>The actor is the synchronisation primitive every {@link
 * io.aeyer.voidcore.ws.VoidCoreSession} call routes through; it has to be
 * correct in isolation before the proxy and registry land on top.
 */
class SessionActorTest {

    private SessionCore core;
    private SessionActor actor;

    @BeforeEach
    void setUp() {
        WebSocketSession ws = mock(WebSocketSession.class);
        when(ws.getId()).thenReturn("test-session");
        when(ws.isOpen()).thenReturn(true);
        core = new SessionCore(ws, new ObjectMapper(), mock(ProtocolTypeRegistry.class));
        actor = new SessionActor(core);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        actor.shutdown();
        actor.awaitTermination();
    }

    @Test
    void submitReturnsValue() {
        core.setUserId(42L);
        Long got = actor.submit(c -> c.userId());
        assertThat(got).isEqualTo(42L);
    }

    @Test
    void submitVoidStyleReturnsNull() {
        Object got = actor.submit(c -> {
            c.setUserId(99L);
            return null;
        });
        assertThat(got).isNull();
        // And the value really did land on the core.
        Long uid = actor.submit(c -> c.userId());
        assertThat(uid).isEqualTo(99L);
    }

    @Test
    void submitsAreSerialisedInFifoOrder() {
        // Each submit appends to a list — if the actor processed out of order,
        // the list would not be in 0..99 sequence.
        List<Integer> seen = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            int idx = i;
            actor.submit(c -> {
                seen.add(idx);
                return null;
            });
        }
        // After 100 sync submits all complete, list must be in order.
        assertThat(seen).hasSize(100);
        for (int i = 0; i < 100; i++) {
            assertThat(seen.get(i)).isEqualTo(i);
        }
    }

    @Test
    void submitPropagatesRuntimeException() {
        assertThatThrownBy(() -> actor.submit(c -> {
            throw new IllegalStateException("boom");
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");
    }

    @Test
    void submitWrapsCheckedExceptionInRuntime() {
        assertThatThrownBy(() -> actor.submit(c -> {
            try {
                throw new java.io.IOException("io");
            } catch (java.io.IOException e) {
                // We can't throw checked from a Function, so
                // wrap to test the unwrap path.
                throw new RuntimeException(e);
            }
        }))
                .isInstanceOf(RuntimeException.class)
                .hasCauseInstanceOf(java.io.IOException.class);
    }

    @Test
    void enqueueAsyncDoesNotBlockCallerButStillExecutes() throws Exception {
        // Pre-fill the queue with a slow op so the async one has to wait.
        actor.submit(c -> {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        });
        // Async enqueue — caller doesn't block on it.
        long before = System.nanoTime();
        actor.enqueueAsync(c -> {
            c.setHandle("async-set");
            return null;
        });
        long elapsedMs = (System.nanoTime() - before) / 1_000_000;
        // enqueueAsync should be near-instant — well under any actor processing time.
        assertThat(elapsedMs).isLessThan(50);

        // But the value DOES land — sync read after the async write
        // sees the value (FIFO ordering).
        String handle = actor.submit(c -> c.handle());
        assertThat(handle).isEqualTo("async-set");
    }

    @Test
    void reentrantSubmitFromWorkerRunsInline() {
        // From the outer submit's lambda (which runs on the worker), call
        // submit again. Without the re-entrancy short-circuit this would
        // deadlock — outer lambda blocks on reply.get() of an inner Msg
        // that the worker (us) cannot dequeue while busy. With the
        // short-circuit, the inner call applies directly.
        Long got = actor.submit(c -> {
            // Inside the worker thread now. A nested submit() must not
            // deadlock and must see the same core state.
            c.setUserId(123L);
            return actor.submit(c2 -> c2.userId());
        });
        assertThat(got).isEqualTo(123L);
    }

    @Test
    void submitAfterShutdownThrows() throws InterruptedException {
        actor.shutdown();
        actor.awaitTermination();
        assertThatThrownBy(() -> actor.submit(c -> c.userId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shut down");
    }

    @Test
    void swapUnderlyingChangesWsButNotId() {
        // The actor's id is stable across WS swaps (ADR-033 phase 1).
        // Before the swap, currentWsId reflects the original.
        String origId = actor.submit(c -> c.id());
        String origWs = actor.submit(c -> c.currentWsId());
        assertThat(origWs).isEqualTo("test-session");

        // Swap a new WS in via the actor — same single-thread guarantee.
        WebSocketSession next = mock(WebSocketSession.class);
        when(next.getId()).thenReturn("test-session-2");
        when(next.isOpen()).thenReturn(true);
        actor.submit(c -> { c.swapUnderlying(next); return null; });

        // id() unchanged; currentWsId() reflects the new WS; counter reset.
        String idAfter = actor.submit(c -> c.id());
        String wsAfter = actor.submit(c -> c.currentWsId());
        int counterAfter = actor.submit(c -> c.pingsSinceLastPong());
        assertThat(idAfter).isEqualTo(origId);
        assertThat(wsAfter).isEqualTo("test-session-2");
        assertThat(counterAfter).isZero();
    }

    @Test
    void shutdownDrainsCleanly() throws InterruptedException {
        actor.submit(c -> {
            c.setUserId(1L);
            return null;
        });
        actor.shutdown();
        actor.awaitTermination();
        // Core's state stays accessible via direct ref after shutdown
        // (the actor doesn't own the core's lifetime; the registry does).
        assertThat(core.userId()).isEqualTo(1L);
    }
}
