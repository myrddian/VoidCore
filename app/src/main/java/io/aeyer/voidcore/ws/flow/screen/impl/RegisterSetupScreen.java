package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.ws.flow.RegisterDraft;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.flow.screen.Screen;
import io.aeyer.voidcore.ws.flow.screen.ScreenComponent;
import io.aeyer.voidcore.ws.flow.screen.Transition;
import io.aeyer.voidcore.ws.protocol.ServerMessage.InputPrompt;

/** Step 4 of 6 — setup (DAW / synths / monitors, optional). */
@ScreenComponent
public class RegisterSetupScreen implements Screen {

    private static final InputPrompt PROMPT =
            new InputPrompt("line", "setup (DAW / synths / monitors, optional):", 256, null, null);

    @Override public Phase phase() { return Phase.REGISTER_SETUP; }
    @Override public String name() { return "register-setup"; }

    @Override
    public Transition onEnter(BbsContext ctx) {
        ctx.send(PROMPT);
        return Transition.None.INSTANCE;
    }

    @Override
    public Transition onLine(BbsContext ctx, String setup) {
        RegisterDraft d = ctx.session().registerDraft();
        if (d == null) d = RegisterDraft.empty();
        ctx.session().setRegisterDraft(d.withSetup(setup));
        ctx.replaceTopAndEnter(Phase.REGISTER_FOUND_VIA);
        return Transition.None.INSTANCE;
    }

    @Override
    public Transition onCancel(BbsContext ctx) {
        ctx.session().setRegisterDraft(null);
        ctx.replaceTopAndEnter(Phase.LOGIN_HANDLE);
        return Transition.None.INSTANCE;
    }
}
