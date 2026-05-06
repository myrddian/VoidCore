package io.aeyer.voidcore.ws.flow.screen.form;

import io.aeyer.voidcore.ws.flow.Frames;
import io.aeyer.voidcore.ws.flow.layout.Element;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.ScreenApp;
import io.aeyer.voidcore.ws.flow.screen.Transition;
import io.aeyer.voidcore.ws.flow.ui.AppEvent;
import io.aeyer.voidcore.ws.protocol.ServerMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * View-first / edit-on-demand form. Generalises {@link
 * io.aeyer.voidcore.ws.flow.screen.impl.DocumentScreen}'s state machine into
 * a reusable base.
 *
 * <p>State machine:
 * <pre>
 *   VIEW ──E──▶ EDIT_MENU ──letter──▶ EDITING_FIELD ──commit/Esc──▶ EDIT_MENU
 *     │             │                                                    │
 *     │             └──Esc──▶ VIEW                                       │
 *     │                                                                   │
 *     └──Q──▶ popAndExit()                                                │
 * </pre>
 *
 * @param <S> the immutable domain object the form displays / edits
 */
public abstract class MenuFormApp<S> extends ScreenApp {

    private enum UiState { VIEW, EDIT_MENU, EDITING_FIELD }

    private UiState uiState = UiState.VIEW;
    /** When in EDITING_FIELD, which field's letter we entered with. */
    private String editingLetter;

    // ─── Subclass hooks ───────────────────────────────────────────────

    /** Load fresh domain state. Called every compose. Return null → bounce. */
    protected abstract S loadState(BbsContext ctx);

    /** Editable fields, in render order. */
    protected abstract List<FormField<S>> fields(BbsContext ctx, S state);

    /** Element drawn above the field list (typically a Header). */
    protected abstract Element headerElement(BbsContext ctx, S state);

    /** Element drawn below the field list. Default: blank. */
    protected Element footerElement(BbsContext ctx, S state) {
        return new Element.Spacer(0);
    }

    /** Extra menu actions (e.g. [D]elete). Default: none. */
    protected List<MenuAction<S>> menuActions(BbsContext ctx, S state) {
        return List.of();
    }

    /** Banner breadcrumb when this screen is on top. */
    protected abstract String bannerLabel(BbsContext ctx, S state);

    /** Letter that quits the form. Default: "Q". */
    protected String quitLetter() { return "Q"; }

    /** Letter that enters EDIT_MENU. Default: "E". Suppressed if fields() is empty. */
    protected String editLetter() { return "E"; }

    /** Optional cleanup when [Q] is pressed (e.g. clear session pointers). */
    protected void onQuit(BbsContext ctx) { /* default no-op */ }

    // ─── ScreenApp overrides ──────────────────────────────────────────

    @Override
    protected final String bannerLabel(BbsContext ctx) {
        S s = loadState(ctx);
        return s == null ? name().toUpperCase() : bannerLabel(ctx, s);
    }

    @Override
    public Transition onEnter(BbsContext ctx) {
        if (loadState(ctx) == null) {
            ctx.pop();
            return Transition.None.INSTANCE;
        }
        uiState = UiState.VIEW;
        editingLetter = null;
        return super.onEnter(ctx);
    }

    @Override
    protected ServerMessage.InputPrompt defaultInputPrompt(BbsContext ctx) {
        return inputPromptForState(ctx);
    }

    @Override
    protected Element compose(BbsContext ctx) {
        S state = loadState(ctx);
        if (state == null) {
            return new Element.VStack(List.of(new Element.Header(name().toUpperCase(), "(gone)")), 0);
        }

        List<FormField<S>> fieldList = fields(ctx, state);
        List<Element> children = new ArrayList<>();
        children.add(headerElement(ctx, state));

        // Render each field as a TextField (or Editor for MULTI_LINE).
        // Read-only unless we're editing this specific letter.
        List<Element> formChildren = new ArrayList<>();
        String focused = null;
        for (FormField<S> f : fieldList) {
            String value = f.get().apply(state);
            String display = value == null ? "" : value;
            boolean editingThis = uiState == UiState.EDITING_FIELD && f.letter().equals(editingLetter);
            if (editingThis) focused = f.letter();
            switch (f.kind()) {
                case MULTI_LINE -> formChildren.add(new Element.Editor(
                    f.letter(), display,
                    editingThis ? "NORMAL" : "READ_ONLY",
                    "markdown",
                    !editingThis));
                default -> formChildren.add(new Element.TextField(
                    f.letter(), f.label() + ":", display, 200, !editingThis));
            }
        }
        children.add(new Element.Form(name() + "-form", formChildren, focused));
        children.add(footerElement(ctx, state));
        return new Element.VStack(children, 0);
    }

    @Override
    public Transition onKey(BbsContext ctx, String k) {
        S state = loadState(ctx);
        if (state == null) return Transition.None.INSTANCE;

        switch (uiState) {
            case VIEW -> {
                if (k.equals(quitLetter())) {
                    onQuit(ctx);
                    popAndExit(ctx);
                } else if (k.equals(editLetter()) && !fields(ctx, state).isEmpty()) {
                    uiState = UiState.EDIT_MENU;
                    repaintNow(ctx);
                    ctx.send(inputPromptForState(ctx));
                } else {
                    handleMenuAction(ctx, state, k);
                }
            }
            case EDIT_MENU -> {
                if (k.equals(quitLetter())) {
                    uiState = UiState.VIEW;
                    repaintNow(ctx);
                    ctx.send(inputPromptForState(ctx));
                    return Transition.None.INSTANCE;
                }
                FormField<S> field = findField(ctx, state, k);
                if (field != null) {
                    enterField(ctx, state, field);
                    return Transition.None.INSTANCE;
                }
                handleMenuAction(ctx, state, k);
            }
            case EDITING_FIELD -> { /* keystrokes belong to the client widget */ }
        }
        return Transition.None.INSTANCE;
    }

    @Override
    public Transition onCancel(BbsContext ctx) {
        switch (uiState) {
            case VIEW -> {
                onQuit(ctx);
                popAndExit(ctx);
            }
            case EDIT_MENU -> {
                uiState = UiState.VIEW;
                repaintNow(ctx);
                ctx.send(inputPromptForState(ctx));
            }
            case EDITING_FIELD -> {
                uiState = UiState.EDIT_MENU;
                editingLetter = null;
                repaintNow(ctx);
                ctx.send(inputPromptForState(ctx));
            }
        }
        return Transition.None.INSTANCE;
    }

    @Override
    protected void onEvent(BbsContext ctx, AppEvent ev) {
        switch (ev) {
            case AppEvent.FieldCommit fc       -> handleFieldCommit(ctx, fc);
            case AppEvent.FieldCancel fc       -> handleFieldCancel(ctx);
            case AppEvent.EditorCommit ec      -> handleEditorCommit(ctx, ec);
            case AppEvent.EditorCancel ec      -> handleEditorCancel(ctx, ec);
            case AppEvent.EditorSnapshot es    -> { /* MenuFormApp doesn't snapshot multi-line fields */ }
            case AppEvent.FocusMove fm         -> handleFocusMove(ctx, fm);
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────

    private FormField<S> findField(BbsContext ctx, S state, String letter) {
        for (FormField<S> f : fields(ctx, state)) {
            if (f.letter().equals(letter)) return f;
        }
        return null;
    }

    private void enterField(BbsContext ctx, S state, FormField<S> field) {
        switch (field.kind()) {
            case CYCLE -> {
                String current = field.get().apply(state);
                String next = field.editor().nextCycleValue(state, current);
                String err = field.editor().onCommit(ctx, state, next);
                if (err != null) ctx.send(Frames.notify("notifications", err, "alert", 3000));
                repaintNow(ctx);
            }
            case TOGGLE -> {
                String current = field.get().apply(state);
                String next = field.editor().nextToggleValue(state, current);
                String err = field.editor().onCommit(ctx, state, next);
                if (err != null) ctx.send(Frames.notify("notifications", err, "alert", 3000));
                repaintNow(ctx);
            }
            default -> {
                uiState = UiState.EDITING_FIELD;
                editingLetter = field.letter();
                repaintNow(ctx);
                ctx.send(inputPromptForState(ctx));
            }
        }
    }

    private void handleMenuAction(BbsContext ctx, S state, String key) {
        for (MenuAction<S> a : menuActions(ctx, state)) {
            if (a.letter().equals(key)) {
                a.onPress().accept(ctx, state);
                return;
            }
        }
    }

    private void handleFieldCommit(BbsContext ctx, AppEvent.FieldCommit fc) {
        S state = loadState(ctx);
        if (state == null) return;
        FormField<S> field = findField(ctx, state, fc.widgetId());
        if (field == null) return;
        String err = field.editor().onCommit(ctx, state, fc.value());
        if (err != null) {
            ctx.send(Frames.notify("notifications", err, "alert", 3000));
            return;
        }
        uiState = UiState.EDIT_MENU;
        editingLetter = null;
        repaintNow(ctx);
        ctx.send(inputPromptForState(ctx));
    }

    private void handleEditorCommit(BbsContext ctx, AppEvent.EditorCommit ec) {
        S state = loadState(ctx);
        if (state == null) return;
        FormField<S> field = findField(ctx, state, ec.widgetId());
        if (field == null) return;
        String err = field.editor().onCommit(ctx, state, ec.content() == null ? "" : ec.content());
        if (err != null) {
            ctx.send(Frames.notify("notifications", err, "alert", 3000));
            return;
        }
        if ("save_quit".equals(ec.action())) {
            onQuit(ctx);
            popAndExit(ctx);
            return;
        }
        uiState = UiState.EDIT_MENU;
        editingLetter = null;
        repaintNow(ctx);
        ctx.send(inputPromptForState(ctx));
    }

    private void handleEditorCancel(BbsContext ctx, AppEvent.EditorCancel ec) {
        if (uiState == UiState.EDITING_FIELD) {
            uiState = UiState.EDIT_MENU;
            editingLetter = null;
            repaintNow(ctx);
            ctx.send(inputPromptForState(ctx));
        }
    }

    private void handleFieldCancel(BbsContext ctx) {
        if (uiState == UiState.EDITING_FIELD) {
            uiState = UiState.EDIT_MENU;
            editingLetter = null;
            repaintNow(ctx);
            ctx.send(inputPromptForState(ctx));
        }
    }

    private void handleFocusMove(BbsContext ctx, AppEvent.FocusMove fm) {
        if (uiState == UiState.EDITING_FIELD) {
            uiState = UiState.EDIT_MENU;
            editingLetter = null;
            repaintNow(ctx);
            ctx.send(inputPromptForState(ctx));
        }
    }

    private ServerMessage.InputPrompt inputPromptForState(BbsContext ctx) {
        S state = loadState(ctx);
        return switch (uiState) {
            case VIEW -> new ServerMessage.InputPrompt(
                "keystroke",
                viewLabel(ctx, state),
                null, viewValidKeys(ctx, state), null);
            case EDIT_MENU -> new ServerMessage.InputPrompt(
                "keystroke",
                editMenuLabel(ctx, state),
                null, editMenuValidKeys(ctx, state), null);
            case EDITING_FIELD -> new ServerMessage.InputPrompt("none", null, null, null, null);
        };
    }

    private String viewLabel(BbsContext ctx, S state) {
        StringBuilder sb = new StringBuilder(name() + ":  ");
        if (state != null && !fields(ctx, state).isEmpty()) {
            sb.append("[").append(editLetter()).append("] edit   ");
        }
        if (state != null) {
            for (MenuAction<S> a : menuActions(ctx, state)) {
                sb.append("[").append(a.letter()).append("] ").append(a.label()).append("   ");
            }
        }
        sb.append("[").append(quitLetter()).append("] back");
        return sb.toString();
    }

    private String viewValidKeys(BbsContext ctx, S state) {
        StringBuilder sb = new StringBuilder();
        if (state != null && !fields(ctx, state).isEmpty()) sb.append(editLetter());
        if (state != null) {
            for (MenuAction<S> a : menuActions(ctx, state)) sb.append(a.letter());
        }
        sb.append(quitLetter());
        return sb.toString();
    }

    private String editMenuLabel(BbsContext ctx, S state) {
        StringBuilder sb = new StringBuilder("edit:  ");
        for (FormField<S> f : fields(ctx, state)) {
            sb.append("[").append(f.letter()).append("] ").append(f.label().toLowerCase()).append("   ");
        }
        for (MenuAction<S> a : menuActions(ctx, state)) {
            sb.append("[").append(a.letter()).append("] ").append(a.label()).append("   ");
        }
        sb.append("[Esc] cancel");
        return sb.toString();
    }

    private String editMenuValidKeys(BbsContext ctx, S state) {
        StringBuilder sb = new StringBuilder();
        for (FormField<S> f : fields(ctx, state)) sb.append(f.letter());
        for (MenuAction<S> a : menuActions(ctx, state)) sb.append(a.letter());
        sb.append(quitLetter());
        return sb.toString();
    }
}
