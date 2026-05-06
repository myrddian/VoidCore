package io.aeyer.voidcore.ws.flow.screen.form;

/**
 * What kind of widget renders a field. The framework reads this to
 * decide between TextField, Editor, and the cycle/toggle flavours
 * (which still use TextField on the wire — the cycle/toggle behaviour
 * is server-side bookkeeping).
 */
public enum FieldKind {
    /** Single-line input. Renders as Element.TextField; commit on Enter. */
    SINGLE_LINE,
    /** Multi-line vim-modal editor. Renders as Element.Editor (markdown highlighting). */
    MULTI_LINE,
    /** Server-side cycle through a fixed list. Renders as read-only TextField; letter
     *  press in the parent menu calls FieldEditor.nextCycleValue and commits in one step. */
    CYCLE,
    /** Binary toggle — degenerate cycle. */
    TOGGLE,
    /** WizardFormApp only — variable-length list. Blank submit ends the loop. */
    VARIABLE_LIST
}
