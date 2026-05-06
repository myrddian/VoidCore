package io.aeyer.voidcore.ws.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Function;

/**
 * The thing that runs a {@link SessionCore} on its own virtual thread.
 *
 * <h2>Mental model</h2>
 *
 * <p>Every operation on the session is enqueued as a {@link Msg}. The
 * actor's worker thread takes one message at a time, applies its lambda
 * against the {@link VoidCoreSession} core, and completes a
 * {@link CompletableFuture} with the result. Callers (the
 * {@code SessionProxy}) submit and block on the future — synchronous
 * call semantics, single-threaded execution semantics. Two threads can
 * never read or write the core's state concurrently because only the
 * worker thread ever touches it.
 *
 * <h2>Self-calls bypass the queue</h2>
 *
 * <p>Inside {@link SessionCore}, one method calling another goes
 * through the direct {@code this.foo(...)} reference. The actor never
 * sees those calls. This avoids the obvious "actor deadlocks waiting
 * for its own queue" trap and matches the Ciotola pattern referenced in
 * the design discussion.
 *
 * <h2>Bus events are async</h2>
 *
 * <p>Bus listener closures don't call session methods directly — they
 * call {@link #enqueueAsync} which fires-and-forgets a {@link Msg}
 * onto the queue. The publisher returns immediately; the actor processes
 * the event when it gets to it. This structurally rules out the
 * synchronous publish → onEvent → onEnter recursion that bit us in v1.5.
 *
 * <h2>Shutdown</h2>
 *
 * <p>{@link #shutdown} signals the worker via a sentinel "poison" message
 * so the queue's {@code take()} unblocks cleanly, then joins the
 * worker. Submitting after shutdown throws {@link IllegalStateException}.
 */
public final class SessionActor {

    private static final Logger log = LoggerFactory.getLogger(SessionActor.class);

    /**
     * Sentinel used to wake the worker on shutdown. The lambda is a no-op;
     * the loop checks identity (==) to exit.
     */
    private static final Msg POISON = new Msg(c -> null, new CompletableFuture<>());

    private final SessionCore core;
    private final BlockingQueue<Msg> queue;
    private final Thread worker;
    private volatile boolean running = true;

    public SessionActor(SessionCore core) {
        this.core = core;
        this.queue = new LinkedBlockingQueue<>();
        this.worker = Thread.ofVirtual()
                .name("session-actor-" + core.id())
                .start(this::loop);
    }

    private void loop() {
        log.debug("[ws-trace] actor-start id={}", core.id());
        try {
            while (running) {
                Msg msg = queue.take();
                if (msg == POISON) break;
                try {
                    Object result = msg.op.apply(core);
                    msg.reply.complete(result);
                } catch (Throwable t) {
                    // Any exception thrown by the core call propagates
                    // back to the caller through the future.
                    msg.reply.completeExceptionally(t);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            log.warn("[ws-trace] actor-stop id={} drained-msgs={}",
                    core.id(), queue.size());
        }
    }

    /**
     * Submit a unit of work and block until it completes. The lambda
     * runs on the actor's worker thread; the calling thread waits on
     * the result future. Any exception thrown by the lambda is
     * unwrapped and rethrown here.
     *
     * <h3>Re-entrant short-circuit</h3>
     *
     * <p>If the caller is already running on this actor's worker thread,
     * the lambda is applied directly against the core and we never touch
     * the queue. Without this, a screen invoked from a bus-event message
     * (running on the worker) calling back through
     * {@code SessionProxy} — say {@code ctx.session().userId()} — would
     * enqueue a message and block on the future, while the only thread
     * that could complete that future (us) is sitting in
     * {@code reply.get()}. Classic actor self-call deadlock.
     *
     * <p>Per-actor serialisation is preserved: re-entrant calls run on
     * the same single worker thread, so two threads still never read or
     * write the core's state concurrently.
     *
     * @param op function applied against the core; may return {@code null}
     *           for void-style operations
     * @return whatever {@code op} returned
     * @throws RuntimeException wrapping any checked exception thrown
     *         by the lambda
     */
    public <R> R submit(Function<SessionCore, R> op) {
        if (Thread.currentThread() == worker) {
            // Re-entrant call from inside the actor's lambda — apply
            // directly. Same thread = same serialisation guarantee.
            return op.apply(core);
        }
        if (!running) {
            throw new IllegalStateException(
                    "SessionActor for id=" + core.id() + " has been shut down");
        }
        CompletableFuture<Object> reply = new CompletableFuture<>();
        @SuppressWarnings("unchecked")
        Function<SessionCore, Object> erased =
                (Function<SessionCore, Object>) (Function<?, ?>) op;
        queue.add(new Msg(erased, reply));
        try {
            @SuppressWarnings("unchecked")
            R result = (R) reply.get();
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted waiting for SessionActor result", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            if (cause instanceof Error err) throw err;
            throw new RuntimeException(cause);
        }
    }

    /**
     * Fire-and-forget enqueue — the lambda runs on the actor's thread
     * but the caller never blocks for a result. Used by bus listener
     * closures to deliver {@code OnBusEvent} so a synchronous publish
     * never crosses thread/state boundaries.
     */
    public void enqueueAsync(Function<SessionCore, ?> op) {
        if (!running) {
            log.warn("[ws-trace] actor-enqueue-after-shutdown id={}", core.id());
            return;
        }
        @SuppressWarnings("unchecked")
        Function<SessionCore, Object> erased =
                (Function<SessionCore, Object>) (Function<?, ?>) op;
        // Discard reply — caller doesn't care, and we don't want to
        // accumulate uncompleted futures.
        CompletableFuture<Object> drop = new CompletableFuture<>();
        queue.add(new Msg(erased, drop));
    }

    /** Cooperative shutdown — wakes the worker via the poison sentinel. */
    public void shutdown() {
        if (!running) return;
        running = false;
        queue.add(POISON);
    }

    /** Wait for the worker to exit. Useful in tests. */
    public void awaitTermination() throws InterruptedException {
        worker.join();
    }

    /** Visible for tests. */
    int queueDepth() {
        return queue.size();
    }

    /** Visible for tests / introspection. */
    String sessionId() {
        return core.id();
    }

    /**
     * One unit of actor work — a lambda + a reply future. {@link #op}
     * is erased to {@code Function<SessionCore, Object>} so a single
     * queue can carry messages of any return type; {@link SessionActor#submit}
     * casts the result back at the boundary.
     */
    record Msg(Function<SessionCore, Object> op, CompletableFuture<Object> reply) {}
}
