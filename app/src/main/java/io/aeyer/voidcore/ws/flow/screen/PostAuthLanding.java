package io.aeyer.voidcore.ws.flow.screen;

import io.aeyer.voidcore.auth.UserRepository.UserRow;
import io.aeyer.voidcore.ws.VoidCoreSession;

/**
 * Single-method callback {@link io.aeyer.voidcore.ws.flow.ScreenRouter}
 * implements so {@link AuthFinaliser} can hand control back for
 * post-auth navigation (intent-based deep-link landing, restore from
 * saved {@code current_screen}, or fall back to the main menu).
 *
 * <p>Why a separate one-method interface and not a direct dependency
 * on {@code ScreenRouter}: AuthFinaliser is reachable from screen
 * impls (via {@link BbsServices}); ScreenRouter takes the screen
 * list at construction; the cycle would force {@code @Lazy
 * ScreenRouter} on AuthFinaliser. With this small interface and an
 * {@code @Lazy PostAuthLanding} dep, the {@code @Lazy} surface area
 * is exactly one method instead of the whole router. Pragmatic
 * minimal-cycle workaround until the deeper screen-registry-as-leaf
 * refactor (see ADR-030's forward-decision sibling text).
 */
public interface PostAuthLanding {

    /**
     * Land an authenticated session on the right post-auth screen.
     * Priority: {@code pendingIntent} (deep-link), then persisted
     * {@code current_screen}, then main menu. Per SPEC §4.6 / §13.
     */
    void applyPostAuth(VoidCoreSession session, UserRow user);
}
