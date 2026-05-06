package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.ws.flow.RegisterDraft;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.flow.screen.Screen;
import io.aeyer.voidcore.ws.flow.screen.ScreenComponent;
import io.aeyer.voidcore.ws.flow.screen.Transition;
import io.aeyer.voidcore.ws.protocol.ServerMessage.InputPrompt;

/** Step 3 of 6 — location (optional). */
@ScreenComponent
public class RegisterLocationScreen implements Screen {

    private static final InputPrompt PROMPT =
            new InputPrompt("line", "location (where on Earth, optional):", 128, null, null);

    @Override public Phase phase() { return Phase.REGISTER_LOCATION; }
    @Override public String name() { return "register-location"; }

    @Override
    public Transition onEnter(BbsContext ctx) {
        ctx.send(PROMPT);
        return Transition.None.INSTANCE;
    }

    @Override
    public Transition onLine(BbsContext ctx, String loc) {
        RegisterDraft d = ctx.session().registerDraft();
        if (d == null) d = RegisterDraft.empty();
        ctx.session().setRegisterDraft(d.withLocation(loc));
        ctx.replaceTopAndEnter(Phase.REGISTER_SETUP);
        return Transition.None.INSTANCE;
    }

    @Override
    public Transition onCancel(BbsContext ctx) {
        ctx.session().setRegisterDraft(null);
        ctx.replaceTopAndEnter(Phase.LOGIN_HANDLE);
        return Transition.None.INSTANCE;
    }
}
