package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.ws.flow.RegisterDraft;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.flow.screen.Screen;
import io.aeyer.voidcore.ws.flow.screen.ScreenComponent;
import io.aeyer.voidcore.ws.flow.screen.Transition;
import io.aeyer.voidcore.ws.protocol.ServerMessage.InputPrompt;

/** Step 5 of 6 — how did you hear about us (optional). */
@ScreenComponent
public class RegisterFoundViaScreen implements Screen {

    private static final InputPrompt PROMPT =
            new InputPrompt("line", "how did you hear about this board (optional):", 256, null, null);

    @Override public Phase phase() { return Phase.REGISTER_FOUND_VIA; }
    @Override public String name() { return "register-found-via"; }

    @Override
    public Transition onEnter(BbsContext ctx) {
        ctx.send(PROMPT);
        return Transition.None.INSTANCE;
    }

    @Override
    public Transition onLine(BbsContext ctx, String foundVia) {
        RegisterDraft d = ctx.session().registerDraft();
        if (d == null) d = RegisterDraft.empty();
        ctx.session().setRegisterDraft(d.withFoundVia(foundVia));
        ctx.replaceTopAndEnter(Phase.REGISTER_FAV_GENRES);
        return Transition.None.INSTANCE;
    }

    @Override
    public Transition onCancel(BbsContext ctx) {
        ctx.session().setRegisterDraft(null);
        ctx.replaceTopAndEnter(Phase.LOGIN_HANDLE);
        return Transition.None.INSTANCE;
    }
}
