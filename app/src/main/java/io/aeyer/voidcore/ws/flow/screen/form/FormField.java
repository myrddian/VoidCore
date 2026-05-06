package io.aeyer.voidcore.ws.flow.screen.form;

import io.aeyer.voidcore.ws.flow.screen.BbsContext;

import java.util.function.Function;

/**
 * Declares one editable field on a {@link MenuFormApp}.
 *
 * @param <S> the domain object type the form edits
 * @param letter   keystroke that selects this field from the EDIT_MENU (single uppercase char)
 * @param label    human-readable label (e.g. "Title")
 * @param get      pull current display value out of S; null/blank rendered as "&lt;unset&gt;"
 * @param kind     widget kind (drives Element.TextField vs Element.Editor)
 * @param editor   commit / cycle handler, called by framework
 */
public record FormField<S>(
    String letter,
    String label,
    Function<S, String> get,
    FieldKind kind,
    FieldEditor<S> editor
) {

    /** Subclasses implement this. */
    public interface FieldEditor<S> {

        /**
         * Persist the new value. Return null on success, or an error
         * message to display as an alert toast (the field stays focused).
         * For CYCLE / TOGGLE kinds this is called by the framework after
         * {@link #nextCycleValue} chooses the new value.
         */
        String onCommit(BbsContext ctx, S state, String newValue);

        /** CYCLE kind only: return the next value in the cycle. Default: identity. */
        default String nextCycleValue(S state, String currentValue) { return currentValue; }

        /** TOGGLE kind only: return the next binary value. Default: identity. */
        default String nextToggleValue(S state, String currentValue) { return currentValue; }
    }
}
