package io.aeyer.voidcore.ws.flow.screen.form;

import io.aeyer.voidcore.ws.flow.screen.BbsContext;

import java.util.function.BiConsumer;

/**
 * A non-field menu entry on a {@link MenuFormApp} — e.g. "[D]elete this file"
 * or "[B]an this user". Letters here MUST NOT collide with any FormField letter.
 *
 * @param letter   single uppercase keystroke
 * @param label    description for the KeyMenu (e.g. "delete this file")
 * @param style    colour hint for the keystroke letter (typically "bright_red"
 *                 for destructive, null for default)
 * @param onPress  invoked when the user presses {@code letter} from EDIT_MENU.
 *                 The handler MAY call {@link io.aeyer.voidcore.ws.flow.screen.BbsContext#push}
 *                 to push a follow-up screen (e.g. a confirm dialog).
 */
public record MenuAction<S>(
    String letter,
    String label,
    String style,
    BiConsumer<BbsContext, S> onPress
) {}
