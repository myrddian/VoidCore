package io.aeyer.voidcore.ws.flow.screen;

import io.aeyer.voidcore.ws.VoidCoreSession;

/**
 * Stack-based navigation primitive that {@link Screen} implementations
 * call to move between screens. ScreenRouter implements this; every
 * {@link BbsContext} exposes the live navigator.
 *
 * <p>The model: each session has a stack of screens. The top of the
 * stack is the active screen — its {@code onEnter} runs when it
 * becomes the top, its handlers receive input, and it sleeps when
 * something is pushed on top of it. On pop, the previous screen
 * resumes (its {@code onEnter} re-fires so it can re-paint).
 *
 * <p>Pre-auth screens (login, register) deliberately do <strong>not</strong>
 * participate in the stack — they're a linear state machine that runs
 * before the stack semantics begin. After auth succeeds, the stack is
 * seeded with the main menu and normal navigation begins.
 *
 * <p>Pop semantics:
 * <ul>
 *   <li>If the stack still has at least one screen below: pop and
 *       re-enter the new top.</li>
 *   <li>If popping would empty the stack: trigger the configured
 *       <em>root guard</em> for the leaving screen — typically
 *       {@code logout} for the main menu.</li>
 * </ul>
 */
public interface Navigator {

    /**
     * Push a screen on top of the stack and dispatch its
     * {@code onEnter}. Previous top is now sleeping; control returns
     * to it on the next {@link #pop(VoidCoreSession)}.
     */
    void push(VoidCoreSession session, Phase phase);

    /**
     * Pop the top of the stack. If something remains underneath, that
     * screen's {@code onEnter} re-fires so it can re-paint. If the
     * stack would become empty, the root-guard for the popping screen
     * fires (typically logout for the main menu).
     */
    void pop(VoidCoreSession session);

    /**
     * Replace the top of the stack without dispatching any onEnter.
     * Used by the pre-auth flow (login → password → main menu) where
     * the linear state machine wants to advance without stack
     * semantics.
     */
    void replaceTop(VoidCoreSession session, Phase phase);

    /**
     * Replace the top of the stack and dispatch the new top's
     * {@code onEnter}, refreshing bus subscriptions.
     *
     * <p>Intended for linear, stack-flat workflows that step forward
     * without nesting (e.g. NetMail compose: TO → SUBJECT → BODY).
     * Pop from any step in the chain returns to whatever was below
     * the chain (the inbox), not to the previous step — that's the
     * point of {@code replaceTop} over {@code push}.
     */
    void replaceTopAndEnter(VoidCoreSession session, Phase phase);

    /**
     * The current top of the stack, or {@code null} if the session
     * has no stack yet (pre-auth).
     */
    Phase currentPhase(VoidCoreSession session);
}
