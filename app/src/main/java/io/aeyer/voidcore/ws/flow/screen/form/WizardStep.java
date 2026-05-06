package io.aeyer.voidcore.ws.flow.screen.form;

import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Declares one step on a {@link WizardFormApp}.
 *
 * @param <S>      the wizard's accumulator type
 * @param label    prompt label (e.g. "Subject")
 * @param kind     widget kind (SINGLE_LINE / MULTI_LINE / VARIABLE_LIST)
 * @param setter   called on commit — mutates S to record the new value.
 *                 For VARIABLE_LIST the value is the just-committed line; the
 *                 framework calls setter once per non-blank submission.
 * @param validate empty Optional = valid; populated = error message to display
 *                 (the step stays focused). For VARIABLE_LIST, this is called
 *                 on the BLANK submission with the accumulated list size as the
 *                 string (e.g. "2") — implementations check min/max and return
 *                 an error if the size is outside bounds.
 */
public record WizardStep<S>(
    String label,
    FieldKind kind,
    BiConsumer<S, String> setter,
    Function<String, Optional<String>> validate
) {

    /** Convenience: a step with no validation (always valid). */
    public static <S> WizardStep<S> ofKind(
            String label, FieldKind kind, BiConsumer<S, String> setter) {
        return new WizardStep<>(label, kind, setter, v -> Optional.empty());
    }
}
