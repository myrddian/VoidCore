package io.aeyer.voidcore.ws.flow.screen;

import java.util.List;

/**
 * A top-level renderable surface in the BBS. Each {@code Screen}
 * implementation owns one or more {@link Phase} values and handles all
 * input that arrives while the user is in that phase: keystrokes,
 * line submits, and Esc/cancel.
 *
 * <p>Per ADR-025 / SPEC-screens.md, the v1.4 refactor replaces the
 * monolithic {@code ScreenRouter} dispatch with one {@code Screen}
 * implementation per logical screen. Subclasses are Spring components
 * discovered by component scan; the {@link io.aeyer.voidcore.ws.flow.ScreenRouter}
 * builds a {@code Map<Phase, Screen>} at startup and dispatches every
 * input event to the right screen.
 *
 * <p>v1.4 PR-A keeps existing screens emitting {@code Frames.update}
 * calls directly (legacy {@code Transition.None} return); PR-B
 * introduces the {@code LayoutTree} data model and screens migrate to
 * return layout trees that the renderer paints, applying the active
 * theme.
 */
public interface Screen {

    /** Primary phase this screen handles. */
    Phase phase();

    /**
     * Stable display name for logging, audit rows, and the
     * {@code current_screen} JSONB persistence (SPEC §3). Lowercase,
     * kebab-case (e.g. {@code "main-menu"}, {@code "sysop-release-edit"}).
     */
    String name();

    /**
     * Called when the user enters this screen. Implementations paint
     * the initial frame and emit any prompt. Return value indicates
     * whether the router should take any further action (typically
     * {@link Transition.None} during PR-A, {@link Transition.Stay}
     * with a layout tree once PR-B lands).
     */
    Transition onEnter(BbsContext ctx);

    /**
     * Keystroke-mode input. {@code key} is uppercase per SPEC §4.3.
     * Default: no-op (return {@link Transition.None}).
     */
    default Transition onKey(BbsContext ctx, String key) {
        return Transition.None.INSTANCE;
    }

    /**
     * Line-mode input. Body is the user's submitted text (untrimmed —
     * implementations decide whether to trim per field).
     * Default: no-op.
     */
    default Transition onLine(BbsContext ctx, String text) {
        return Transition.None.INSTANCE;
    }

    /**
     * Esc / cancel. Most screens return {@link Transition.Back} to pop
     * to the previous screen, or {@link Transition.To} to redirect to
     * a sensible default (e.g. main menu).
     * Default: no-op.
     */
    default Transition onCancel(BbsContext ctx) {
        return Transition.None.INSTANCE;
    }

    /**
     * Topics this screen wants to be notified about while it's the
     * active top of the navigation stack. Per ADR-027 / SPEC §13.
     *
     * <p>The {@link io.aeyer.voidcore.ws.flow.screen.Navigator} subscribes
     * the session to these topics on push (after {@code onEnter}) and
     * unsubscribes on pop. A topic notification fires
     * {@link #onEvent(BbsContext, String)}.
     *
     * <p>Topics are arbitrary strings — convention is the resource
     * name (e.g. {@code "oneliners"}, {@code "documents"},
     * {@code "thread:42"}). No payload, no replay; the screen
     * re-reads its source of truth on notification.
     *
     * <p>Default: no topics (screen ignores cross-session changes).
     */
    default List<String> topics(BbsContext ctx) {
        return List.of();
    }

    /**
     * Called when a topic the screen subscribed to via
     * {@link #topics(BbsContext)} is notified. Default behaviour is
     * to re-fire {@link #onEnter(BbsContext)} — i.e. re-paint with a
     * fresh DB read, which matches what the existing imperative
     * broadcasts did.
     *
     * <p>Screens wanting finer-grained behaviour (e.g. chat appending
     * one new message instead of re-painting all of them) override
     * this and decide per-topic.
     *
     * <p>Per ADR-027 there is intentionally no payload — the bus
     * delivers only the fact that {@code topic} fired. The screen
     * goes back to its data source for the actual content.
     */
    default Transition onEvent(BbsContext ctx, String topic) {
        return onEnter(ctx);
    }
}
