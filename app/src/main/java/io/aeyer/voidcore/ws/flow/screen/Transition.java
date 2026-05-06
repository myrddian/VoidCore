package io.aeyer.voidcore.ws.flow.screen;

import java.util.Map;

/**
 * What a {@link Screen} handler returns to tell the
 * {@link io.aeyer.voidcore.ws.flow.ScreenRouter} what to do next. Sealed so
 * the dispatcher's switch is total at compile time.
 *
 * <p>Per SPEC-screens.md §2:
 * <ul>
 *   <li>{@link Stay} — stay on the current screen; optional re-render</li>
 *   <li>{@link To} — go to another phase, with optional payload args</li>
 *   <li>{@link Back} — pop the screen stack (consults router state)</li>
 *   <li>{@link End} — close the connection (logout / goodbye)</li>
 *   <li>{@link None} — input was already handled by the screen itself
 *       (e.g. the screen called router helpers directly during the
 *       transitional period); no further router action</li>
 * </ul>
 *
 * <p>{@link None} is a transitional concession for v1.4 PR-A: as
 * screens are extracted incrementally, some still call the legacy
 * helpers on {@link io.aeyer.voidcore.ws.flow.ScreenRouter} directly.
 * Once every screen returns a real {@link Transition}, this variant
 * disappears in PR-A's final cleanup.
 */
public sealed interface Transition {

    /** Stay on this screen. {@code refresh} may be null (input handled, no repaint). */
    record Stay(LayoutTreePlaceholder refresh) implements Transition {}

    /** Move to another phase. {@code args} carries optional payload (e.g. selected id). */
    record To(Phase next, Map<String, Object> args) implements Transition {
        public static To of(Phase p) { return new To(p, Map.of()); }
        public static To of(Phase p, String key, Object value) {
            return new To(p, Map.of(key, value));
        }
    }

    /** Pop one entry from the screen stack. */
    record Back() implements Transition {
        public static final Back INSTANCE = new Back();
    }

    /** Close the WS connection cleanly with the given reason. */
    record End(String reason) implements Transition {
        public static final End GOODBYE = new End("goodbye");
    }

    /** Transitional: screen handled it via legacy helpers; router takes no further action. */
    record None() implements Transition {
        public static final None INSTANCE = new None();
    }

    /**
     * Stub for the future {@code LayoutTree} type that PR-B introduces.
     * In PR-A, screens still emit {@code Frames.update} directly, so
     * {@link Stay#refresh} is always null and this placeholder is never
     * dereferenced. PR-B replaces it with the real {@code LayoutTree}
     * record + node hierarchy.
     */
    interface LayoutTreePlaceholder {}
}
