package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.flow.screen.Screen;
import io.aeyer.voidcore.ws.flow.screen.ScreenComponent;
import io.aeyer.voidcore.ws.flow.screen.Transition;
import io.aeyer.voidcore.ws.protocol.ServerMessage.InputPrompt;

/**
 * Step 6 of 6 — favourite genres (optional). Final step delegates to
 * {@link io.aeyer.voidcore.ws.flow.screen.AuthFinaliser#finaliseRegistration}
 * which owns the {@link io.aeyer.voidcore.auth.AuthService#register} call
 * and the post-auth pipeline / failure-bounce paths.
 *
 * <p>[Esc] cancels — drops draft, returns to LOGIN_HANDLE.
 */
@ScreenComponent
public class RegisterFavGenresScreen implements Screen {

    private static final InputPrompt PROMPT =
            new InputPrompt("line", "favourite genres (optional):", 256, null, null);

    @Override public Phase phase() { return Phase.REGISTER_FAV_GENRES; }
    @Override public String name() { return "register-fav-genres"; }

    @Override
    public Transition onEnter(BbsContext ctx) {
        ctx.send(PROMPT);
        return Transition.None.INSTANCE;
    }

    @Override
    public Transition onLine(BbsContext ctx, String genres) {
        // AuthFinaliser applies the input to the draft, calls
        // auth.register, and either lands on the menu (success
        // -> onSuccess -> applyPostAuth -> resetStack(MENU)) or
        // sets phase back to LOGIN_HANDLE on failure. The failure
        // path also re-bases the stack via replaceTopAndEnter so
        // we don't leave register screens dangling.
        ctx.services().authFinaliser().finaliseRegistration(ctx.session(), genres);
        return Transition.None.INSTANCE;
    }

    @Override
    public Transition onCancel(BbsContext ctx) {
        ctx.session().setRegisterDraft(null);
        ctx.replaceTopAndEnter(Phase.LOGIN_HANDLE);
        return Transition.None.INSTANCE;
    }
}
