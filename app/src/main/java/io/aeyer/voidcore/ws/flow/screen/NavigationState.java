package io.aeyer.voidcore.ws.flow.screen;

import io.aeyer.voidcore.ws.VoidCoreSession;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-session navigation stack storage. Pure data; zero behaviour.
 *
 * <p>Internally each layer is a {@link Frame} = ({@link ScreenRoute}, live
 * {@link Screen} instance). The router pushes a fresh instance on every push
 * (minted via Spring's {@code ObjectProvider} so prototype-scoped beans get a
 * new instance each call); that instance lives on the stack for the duration
 * of the layer and is GC'd when the layer pops. This is the fix for the
 * multi-session state-leak bug — see {@link ScreenAppComponent}.
 *
 * <p>The legacy read-only API ({@link #currentPhase}, {@link #peek},
 * {@link #isEmpty}, {@link #clear}) is preserved as thin shims so leaf
 * services that only need to know "what phase is the user on?"
 * (e.g. {@code MentionService} for same-room peer notification
 * suppression) keep working unchanged. Write API is Frame-based —
 * {@link #pushFrame}, {@link #popFrame}, {@link #peekFrame},
 * {@link #replaceTopFrame}, {@link #resetFrame}.
 *
 * <p>Conditional on {@code SessionService} so the bean drops out
 * cleanly in DB-less test profiles, mirroring {@code BbsServices}.
 */
@Component
@ConditionalOnBean(io.aeyer.voidcore.auth.SessionService.class)
public class NavigationState {

    private final Map<String, Deque<Frame>> stacks = new ConcurrentHashMap<>();

    // ─── Frame-based write API (router-only) ─────────────────────────

    /** Push a frame onto the session's stack. Initialises the stack if absent. */
    public void pushFrame(VoidCoreSession session, Frame frame) {
        stacks.computeIfAbsent(session.id(), k -> new ArrayDeque<>()).push(frame);
    }

    /**
     * Pop the top of the stack. Returns the popped frame, or
     * {@code null} if the stack was empty / absent. Callers
     * typically check {@link #peekFrame} or {@link #isEmpty} after to
     * decide whether the root-guard should fire.
     */
    public Frame popFrame(VoidCoreSession session) {
        Deque<Frame> stack = stacks.get(session.id());
        if (stack == null || stack.isEmpty()) return null;
        return stack.pop();
    }

    /** Peek the top of the stack without removing it. */
    public Frame peekFrame(VoidCoreSession session) {
        Deque<Frame> stack = stacks.get(session.id());
        return stack == null ? null : stack.peek();
    }

    /**
     * Replace the top of the stack with {@code frame}. If the
     * stack was empty, just pushes. Returns the previous top, or
     * {@code null} if the stack was empty.
     */
    public Frame replaceTopFrame(VoidCoreSession session, Frame frame) {
        Deque<Frame> stack = stacks.computeIfAbsent(
                session.id(), k -> new ArrayDeque<>());
        Frame prev = stack.isEmpty() ? null : stack.pop();
        stack.push(frame);
        return prev;
    }

    /**
     * Reset the stack to a single frame. Used by post-auth landing
     * (auth.success → resetFrame(MENU)) and by restore /
     * intent paths that need to seed the stack for a deep-link.
     */
    public void resetFrame(VoidCoreSession session, Frame initial) {
        Deque<Frame> stack = new ArrayDeque<>();
        stack.push(initial);
        stacks.put(session.id(), stack);
    }

    /**
     * Snapshot the session's stack as a list ordered bottom → top
     * (i.e. root MENU first, current top last). Used by the AttachWs
     * reconnect path to repaint every layer of the nav stack on the
     * fresh WebSocket. Each frame carries the live {@link Screen}
     * instance, so callers replay {@code onEnter} on the actual
     * per-session instances rather than re-resolving via a singleton
     * map.
     */
    public List<Frame> snapshotFramesBottomToTop(VoidCoreSession session) {
        Deque<Frame> stack = stacks.get(session.id());
        if (stack == null || stack.isEmpty()) return Collections.emptyList();
        List<Frame> bottomToTop = new ArrayList<>(stack);
        // ArrayDeque iterator goes top → bottom (the order push/pop
        // operates on); reverse so the caller gets root-first ordering.
        Collections.reverse(bottomToTop);
        return bottomToTop;
    }

    // ─── Phase-only read API (thin shims; stable for external callers) ──

    /** Top route of the stack, or {@code null} if empty / no stack yet. */
    public ScreenRoute currentRoute(VoidCoreSession session) {
        Frame f = peekFrame(session);
        return f == null ? null : f.route();
    }

    /** Top of the stack, or {@code null} if empty / no stack yet. */
    public Phase currentPhase(VoidCoreSession session) {
        ScreenRoute route = currentRoute(session);
        return route == null ? null : route.corePhaseOrNull();
    }

    /** Peek the top phase without removing it. Same as {@link #currentPhase}. */
    public Phase peek(VoidCoreSession session) {
        return currentPhase(session);
    }

    /** {@code true} if the session has no stack or its stack is empty. */
    public boolean isEmpty(VoidCoreSession session) {
        Deque<Frame> stack = stacks.get(session.id());
        return stack == null || stack.isEmpty();
    }

    /** Clear the session's stack entirely. Called on disconnect. */
    public void clear(VoidCoreSession session) {
        stacks.remove(session.id());
    }
}
