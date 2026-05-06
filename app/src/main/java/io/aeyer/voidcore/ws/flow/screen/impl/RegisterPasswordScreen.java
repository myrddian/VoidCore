package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.ws.flow.Frames;
import io.aeyer.voidcore.ws.flow.RegisterDraft;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.flow.screen.Screen;
import io.aeyer.voidcore.ws.flow.screen.ScreenComponent;
import io.aeyer.voidcore.ws.flow.screen.Transition;
import io.aeyer.voidcore.ws.protocol.ServerMessage.InputPrompt;

/** Step 2 of 6 — set a password (min 8 chars). */
@ScreenComponent
public class RegisterPasswordScreen implements Screen {

    private static final InputPrompt PROMPT =
            new InputPrompt("password", "set a password (min 8 chars):", 4096, null, null);

    @Override public Phase phase() { return Phase.REGISTER_PASSWORD; }
    @Override public String name() { return "register-password"; }

    @Override
    public Transition onEnter(BbsContext ctx) {
        ctx.send(PROMPT);
        return Transition.None.INSTANCE;
    }

    @Override
    public Transition onLine(BbsContext ctx, String pw) {
        if (pw == null || pw.length() < 8) {
            ctx.send(Frames.notify("notifications",
                    "password must be at least 8 characters", "alert", 3500));
            ctx.send(PROMPT);
            return Transition.None.INSTANCE;
        }
        RegisterDraft d = ctx.session().registerDraft();
        if (d == null) d = RegisterDraft.empty();
        ctx.session().setRegisterDraft(d.withPassword(pw));
        ctx.replaceTopAndEnter(Phase.REGISTER_LOCATION);
        return Transition.None.INSTANCE;
    }

    @Override
    public Transition onCancel(BbsContext ctx) {
        ctx.session().setRegisterDraft(null);
        ctx.replaceTopAndEnter(Phase.LOGIN_HANDLE);
        return Transition.None.INSTANCE;
    }
}
