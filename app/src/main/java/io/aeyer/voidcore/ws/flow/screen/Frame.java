package io.aeyer.voidcore.ws.flow.screen;

/**
 * One layer on a session's navigation stack: the route that was pushed plus
 * the live {@link Screen} instance servicing it.
 *
 * <p>Pre-2026-05 the stack stored only {@link Phase} and the router
 * looked up a singleton {@code Map<Phase, Screen>} on every dispatch.
 * That sharing meant mutable per-session state on stateful screens
 * (e.g. {@code DocumentScreen.uiState}, {@code MenuFormApp.editingLetter})
 * leaked across sessions. {@link Frame} pins the live instance to the
 * stack layer that minted it, so each session's state lives on its own
 * instance and is GC'd when the layer pops.
 *
 * <p>For singleton-scoped screens (stateless {@link Screen} impls), the
 * {@code screen} reference is the same instance every push — equivalent
 * to the old behaviour. For prototype-scoped screens (those marked with
 * {@link ScreenAppComponent}), each push mints a fresh instance.
 */
public record Frame(ScreenRoute route, Screen screen) {

    public Frame {
        if (route == null) throw new IllegalArgumentException("route must not be null");
    }

    public Frame(Phase phase, Screen screen) {
        this(ScreenRoute.core(phase), screen);
    }

    public Frame(String screenName, Screen screen) {
        this(ScreenRoute.custom(screenName), screen);
    }

    /**
     * Compatibility shim for older callers that only understand core
     * built-in phases. Returns {@code null} for custom routes.
     */
    public Phase phase() {
        return route.corePhaseOrNull();
    }
}
