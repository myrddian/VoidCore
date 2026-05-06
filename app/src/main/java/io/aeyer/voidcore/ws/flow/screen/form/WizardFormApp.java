package io.aeyer.voidcore.ws.flow.screen.form;

import io.aeyer.voidcore.ws.flow.Frames;
import io.aeyer.voidcore.ws.flow.layout.Element;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.ScreenApp;
import io.aeyer.voidcore.ws.flow.screen.Transition;
import io.aeyer.voidcore.ws.flow.ui.AppEvent;
import io.aeyer.voidcore.ws.protocol.ServerMessage;

import java.util.List;
import java.util.Optional;

/**
 * Sequential step wizard. Subclass declares {@link #steps} and an
 * {@link #onSubmit} handler. The framework navigates next/back/cancel
 * and accumulates state across steps.
 *
 * <p>State machine:
 * <pre>
 *   STEP[0] ──commit──▶ STEP[1] ──commit──▶ ... ──commit──▶ onSubmit + popAndExit
 *      │ Esc                │ Esc
 *      ▼                    ▼
 *   onAbandon           STEP[i-1]
 *   popAndExit
 * </pre>
 *
 * @param <S> the accumulator type
 */
public abstract class WizardFormApp<S> extends ScreenApp {

    private int stepIndex = 0;
    private S state;

    // ─── Subclass hooks ───────────────────────────────────────────────

    protected abstract S newState(BbsContext ctx);
    protected abstract List<WizardStep<S>> steps(BbsContext ctx);
    protected abstract void onSubmit(BbsContext ctx, S state);

    /** Invoked when the user Esc's at step 0. Default: no-op. */
    protected void onAbandon(BbsContext ctx, S state) { /* no-op */ }

    /** Resume hook: subclasses can choose a non-zero starting step. */
    protected int initialStepIndex(BbsContext ctx, S state, List<WizardStep<S>> steps) { return 0; }

    /** Initial widget contents for the current step. Default: empty string. */
    protected String initialValue(BbsContext ctx, S state, WizardStep<S> step, int stepIndex) { return ""; }

    /** Called whenever committed state changes. Default: no-op. */
    protected void onStateChanged(BbsContext ctx, S state, int stepIndex) { /* no-op */ }

    /** Snapshot hook for long-form editors. Default: no-op. */
    protected void onSnapshot(BbsContext ctx, S state, int stepIndex, String content) { /* no-op */ }

    /** Banner label per step. Default: "<NAME> · step n/N". */
    protected String bannerLabel(BbsContext ctx, S state, int step, int total) {
        return name().toUpperCase() + " · step " + (step + 1) + "/" + total;
    }

    /** Header title per step. Default: screen name in upper-case. */
    protected String headerTitle(BbsContext ctx, S state, WizardStep<S> step, int stepIndex, int total) {
        return name().toUpperCase();
    }

    /** Header right annotation per step. Default: "step n/N". */
    protected String headerRightAnnotation(BbsContext ctx, S state, WizardStep<S> step, int stepIndex, int total) {
        return "step " + (stepIndex + 1) + "/" + total;
    }

    /**
     * VARIABLE_LIST counter — subclasses with a list-shaped step override
     * to return the live size. Default: 0 (the framework can't know
     * which field of S holds the list).
     */
    protected int countListEntries() { return 0; }

    // ─── ScreenApp overrides ──────────────────────────────────────────

    @Override
    protected final String bannerLabel(BbsContext ctx) {
        return bannerLabel(ctx, state, stepIndex, steps(ctx).size());
    }

    @Override
    public Transition onEnter(BbsContext ctx) {
        state = newState(ctx);
        List<WizardStep<S>> all = steps(ctx);
        stepIndex = Math.max(0, Math.min(initialStepIndex(ctx, state, all), Math.max(0, all.size() - 1)));
        return super.onEnter(ctx);
    }

    @Override
    protected ServerMessage.InputPrompt defaultInputPrompt(BbsContext ctx) {
        return new ServerMessage.InputPrompt("none", null, null, null, null);
    }

    @Override
    protected Element compose(BbsContext ctx) {
        List<WizardStep<S>> all = steps(ctx);
        if (stepIndex < 0 || stepIndex >= all.size()) {
            return new Element.VStack(List.of(new Element.Header(name().toUpperCase(), null)), 0);
        }
        WizardStep<S> step = all.get(stepIndex);
        String value = initialValue(ctx, state, step, stepIndex);
        Element header = new Element.Header(
            headerTitle(ctx, state, step, stepIndex, all.size()),
            headerRightAnnotation(ctx, state, step, stepIndex, all.size()));
        Element widget = switch (step.kind()) {
            case MULTI_LINE -> new Element.Editor("step", value, "INSERT", "markdown", false);
            default          -> new Element.TextField("step", step.label() + ":", value, 200, false);
        };
        return new Element.VStack(List.of(
            header,
            new Element.Spacer(1),
            new Element.Form(name() + "-form", List.of(widget), "step")
        ), 0);
    }

    @Override
    public Transition onCancel(BbsContext ctx) {
        if (stepIndex == 0) {
            onAbandon(ctx, state);
            popAndExit(ctx);
        } else {
            stepIndex--;
            repaintNow(ctx);
        }
        return Transition.None.INSTANCE;
    }

    @Override
    protected void onEvent(BbsContext ctx, AppEvent ev) {
        switch (ev) {
            case AppEvent.FieldCommit fc       -> handleCommit(ctx, fc.value());
            case AppEvent.FieldCancel fc       -> onCancel(ctx);
            case AppEvent.EditorCommit ec      -> handleCommit(ctx, ec.content() == null ? "" : ec.content());
            case AppEvent.EditorCancel ec      -> onCancel(ctx);
            case AppEvent.EditorSnapshot es    -> onSnapshot(ctx, state, stepIndex, es.content());
            case AppEvent.FocusMove fm         -> { /* steps own their own focus */ }
        }
    }

    private void handleCommit(BbsContext ctx, String value) {
        List<WizardStep<S>> all = steps(ctx);
        if (stepIndex < 0 || stepIndex >= all.size()) return;
        WizardStep<S> step = all.get(stepIndex);

        if (step.kind() == FieldKind.VARIABLE_LIST) {
            String v = value == null ? "" : value;
            if (v.isEmpty()) {
                int size = countListEntries();
                Optional<String> err = step.validate().apply(String.valueOf(size));
                if (err.isPresent()) {
                    ctx.send(Frames.notify("notifications", err.get(), "alert", 3000));
                    return; // stay
                }
                advanceOrSubmit(ctx, all);
                return;
            }
            step.setter().accept(state, v);
            onStateChanged(ctx, state, stepIndex);
            repaintNow(ctx);
            return;
        }

        Optional<String> err = step.validate().apply(value == null ? "" : value);
        if (err.isPresent()) {
            ctx.send(Frames.notify("notifications", err.get(), "alert", 3000));
            return;
        }
        step.setter().accept(state, value == null ? "" : value);
        onStateChanged(ctx, state, stepIndex);
        advanceOrSubmit(ctx, all);
    }

    private void advanceOrSubmit(BbsContext ctx, List<WizardStep<S>> all) {
        if (stepIndex + 1 >= all.size()) {
            onSubmit(ctx, state);
            popAndExit(ctx);
        } else {
            stepIndex++;
            repaintNow(ctx);
        }
    }

    // ─── Test hooks ───────────────────────────────────────────────────

    /** Visible for tests. */
    public int currentStepIndex() { return stepIndex; }
}
