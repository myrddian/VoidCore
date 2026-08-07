package io.aeyer.voidcore.ws.flow.screen;

import io.aeyer.voidcore.ws.flow.Banner;
import io.aeyer.voidcore.ws.flow.Frames;
import io.aeyer.voidcore.ws.flow.layout.Element;
import io.aeyer.voidcore.ws.flow.ui.AppEvent;
import io.aeyer.voidcore.ws.flow.ui.FocusPath;
import io.aeyer.voidcore.ws.protocol.ServerMessage;

/**
 * Abstract base for screens that opt into the v1.6 widget framework.
 * Coexists with the plain {@link Screen} interface — existing screens
 * stay as-is.
 *
 * <p>Subclasses implement three things:
 * <ul>
 *   <li>{@link #appKey(BbsContext)} — scoping key for {@code sessions.app_state}</li>
 *   <li>{@link #compose(BbsContext)} — build the {@link Element} tree</li>
 *   <li>{@link #onEvent(BbsContext, AppEvent)} — domain side-effects</li>
 * </ul>
 *
 * <p>The framework owns {@code onEnter} and {@code onAppEvent} — they're
 * {@code final}. {@code onKey} and {@code onCancel} are non-final so
 * subclasses can override when their state machine needs server-side
 * keystroke routing (e.g. a read-only view + edit-menu state with
 * letter-based field selection).
 *
 * <p>Subclasses that override {@code onKey} or {@code onCancel} are
 * responsible for calling {@link #repaintNow(BbsContext)} after any
 * state change that should update the visible tree.
 */
public abstract class ScreenApp implements Screen {

    private Element tree;
    private String focusPath;
    private int treeVersion;
    private String currentAppKey;
    /** Set when the screen navigated away, so the post-event repaint is skipped. */
    private boolean exited = false;

    /** Per-instance scoping key for {@code sessions.app_state} (e.g. {@code "doc:42"}). */
    protected abstract String appKey(BbsContext ctx);

    /**
     * Subclasses use this instead of {@code ctx.pop()} when they want
     * the screen to exit. It pops the navigator stack AND tells the
     * framework to skip the post-event repaint — otherwise the
     * post-event compose() would emit a stale tree (e.g. the screen's
     * blank placeholder) that clobbers the parent screen's onEnter
     * paint.
     */
    protected final void popAndExit(BbsContext ctx) {
        ctx.pop();
        this.exited = true;
    }

    /**
     * Push another screen and leave. The push equivalent of
     * {@link #popAndExit}, and needed for the same reason: without
     * suppressing the post-event repaint, this screen's {@code compose()}
     * re-emits its own tree <em>over</em> the screen just pushed, so the
     * user sees the new screen's prompt above the old screen's body.
     *
     * <p>Only the event path needs this — a keystroke handler returns
     * before any recompose — but using it on both paths keeps navigation
     * uniform.
     */
    protected final void pushAndExit(BbsContext ctx, Phase phase) {
        ctx.push(phase);
        this.exited = true;
    }

    /**
     * Replace this screen with another and leave. Completes the set beside
     * {@link #popAndExit} and {@link #pushAndExit}: any navigation from the
     * event path has to suppress the post-event repaint, or this screen's
     * tree lands on top of whatever replaced it.
     */
    protected final void replaceTopAndExit(BbsContext ctx, Phase phase) {
        ctx.replaceTopAndEnter(phase);
        this.exited = true;
    }

    /**
     * Short label that appears in the minimised banner when this ScreenApp
     * is on top. Default: the screen's name() in upper-case. Subclasses can
     * override to include richer context (e.g. document slug).
     */
    protected String bannerLabel(BbsContext ctx) {
        return name().toUpperCase();
    }

    /** Build the layout tree from current domain state. */
    protected abstract Element compose(BbsContext ctx);

    /** Domain-level handler for events the framework can't generically resolve. */
    protected abstract void onEvent(BbsContext ctx, AppEvent ev);

    /**
     * What InputPrompt the framework should emit on enter. Subclasses
     * override when they want server-side keystroke routing.
     * Default: {@code "none"} mode — the editor widget owns the keyboard.
     */
    protected ServerMessage.InputPrompt defaultInputPrompt(BbsContext ctx) {
        return new ServerMessage.InputPrompt("none", null, null, null, null);
    }

    /**
     * Recomposes the tree and immediately repaints. Subclasses call this
     * after a state change inside {@code onKey} or {@code onCancel}.
     * Also refreshes {@link #focusPath} from the new tree — without this
     * the wire-level focus field stays at the {@code onEnter} value and
     * downstream renderers see stale focus.
     */
    /**
     * Reflow on resize. Recomposes and repaints without re-entering, so
     * mid-flow state (a wizard's step index, a form's accumulated draft)
     * survives the user rotating their phone.
     */
    @Override
    public void onViewportResize(BbsContext ctx) {
        repaintNow(ctx);
    }

    protected final void repaintNow(BbsContext ctx) {
        this.tree = compose(ctx);
        this.focusPath = FocusPath.firstFocusable(tree).orElse(null);
        repaint(ctx);
    }

    @Override
    public Transition onEnter(BbsContext ctx) {
        this.exited = false;
        this.currentAppKey = appKey(ctx);
        this.tree = compose(ctx);
        this.focusPath = FocusPath.firstFocusable(tree).orElse(null);
        repaint(ctx);
        // Minimise the banner to reclaim vertical space for the editor body.
        ctx.send(Frames.update("banner", 1, Banner.minimalRows(bannerLabel(ctx))));
        // Emit the input prompt appropriate for this screen's initial state.
        ctx.send(defaultInputPrompt(ctx));
        return Transition.None.INSTANCE;
    }

    /**
     * Keystroke handler. Default is a no-op. Subclasses override when
     * their state machine needs server-side keystroke routing. After
     * any state change, subclasses should call {@link #repaintNow(BbsContext)}.
     */
    @Override
    public Transition onKey(BbsContext ctx, String k) {
        return Transition.None.INSTANCE;
    }

    /**
     * Inbound app event from the wire. Routed by {@code ScreenRouter}.
     * Subclasses don't override this — they implement {@link #onEvent}.
     */
    public final void onAppEvent(BbsContext ctx, AppEvent ev) {
        onEvent(ctx, ev);
        // Snapshots are pure write-through — no UI change. Skipping the
        // recompose avoids tearing down the live editor with the just-saved
        // snapshot contents (which would discard the last 15s of typing).
        if (ev instanceof AppEvent.EditorSnapshot) return;
        if (exited) {
            exited = false;
            return;
        }
        this.tree = compose(ctx);   // re-compose from updated domain state
        this.focusPath = FocusPath.firstFocusable(tree).orElse(null);
        repaint(ctx);
    }

    protected final String currentAppKey() { return currentAppKey; }
    protected final String currentFocusPath() { return focusPath; }
    protected final void setFocus(String path) { this.focusPath = path; }

    private void repaint(BbsContext ctx) {
        // BbsContext.send swallows IOException internally — no try/catch needed here.
        ctx.send(Frames.tree("main", ++treeVersion, tree, focusPath));
    }
}
