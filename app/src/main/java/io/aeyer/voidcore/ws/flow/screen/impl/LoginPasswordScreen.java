package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.flow.screen.Screen;
import io.aeyer.voidcore.ws.flow.screen.Transition;
import io.aeyer.voidcore.ws.protocol.ClientMessage;
import io.aeyer.voidcore.ws.protocol.ServerMessage.InputPrompt;
import io.aeyer.voidcore.ws.flow.screen.ScreenComponent;

/**
 * Second half of the login flow — captures the password and dispatches
 * the auth request. Handle is read from the session's transient
 * SessionState (set by {@link LoginHandleScreen} on the previous
 * submit).
 *
 * <p>Auth result handling (success → main menu; failure → re-prompt
 * handle) stays in {@code ScreenRouter.handleLogin} for now; this
 * screen just forwards to that bridge.
 *
 * <p>v1.4 PR-B: rendering moved out of {@code ScreenRouter} into this
 * class.
 */
@ScreenComponent
public class LoginPasswordScreen implements Screen {

    private static final InputPrompt PROMPT =
            new InputPrompt("password", "password:", 4096, null, null);

    @Override
    public Phase phase() {
        return Phase.LOGIN_PASSWORD;
    }

    @Override
    public String name() {
        return "login-password";
    }

    @Override
    public Transition onEnter(BbsContext ctx) {
        ctx.send(PROMPT);
        return Transition.None.INSTANCE;
    }

    @Override
    public Transition onCancel(BbsContext ctx) {
        // Esc from the password prompt — drop back to the handle
        // prompt via the navigator.
        ctx.session().setPendingLoginHandle(null);
        ctx.replaceTopAndEnter(Phase.LOGIN_HANDLE);
        return Transition.None.INSTANCE;
    }

    @Override
    public Transition onLine(BbsContext ctx, String text) {
        String handle = ctx.session().pendingLoginHandle();
        // Forward to AuthFinaliser; it owns success/failure rendering,
        // rate-limit handling, the audit row, presence registration,
        // banner broadcast, auth.ok envelope, and post-auth landing.
        // Auth success will null pendingLoginHandle as part of
        // attachUser — until then we keep the value so a re-prompt
        // path can see it.
        ctx.services().authFinaliser().handleLogin(ctx.session(),
                new ClientMessage.AuthLogin(handle, text, null));
        return Transition.None.INSTANCE;
    }
}
