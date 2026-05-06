package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.auth.UserRepository;
import io.aeyer.voidcore.ws.flow.Frames;
import io.aeyer.voidcore.ws.flow.RegisterDraft;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.flow.screen.Screen;
import io.aeyer.voidcore.ws.flow.screen.ScreenComponent;
import io.aeyer.voidcore.ws.flow.screen.Transition;
import io.aeyer.voidcore.ws.protocol.ServerMessage.InputPrompt;

/**
 * Step 1 of 6 — pick a handle. v1.4 PR-B step 21: linear chain via
 * {@code ctx.replaceTopAndEnter(NEXT)} so [Esc] in any step bounces
 * straight back to login without unwinding through prior steps.
 */
@ScreenComponent
public class RegisterHandleScreen implements Screen {

    private static final InputPrompt PROMPT =
            new InputPrompt("line", "choose a handle (3-16 chars, A-Z 0-9 _ - .):", 16, null, null);

    private final UserRepository users;

    public RegisterHandleScreen(UserRepository users) {
        this.users = users;
    }

    @Override public Phase phase() { return Phase.REGISTER_HANDLE; }
    @Override public String name() { return "register-handle"; }

    @Override
    public Transition onEnter(BbsContext ctx) {
        ctx.send(PROMPT);
        return Transition.None.INSTANCE;
    }

    @Override
    public Transition onLine(BbsContext ctx, String text) {
        String handle = text == null ? "" : text.trim();
        if (handle.isEmpty()) {
            ctx.send(PROMPT);
            return Transition.None.INSTANCE;
        }
        if (!handle.matches("^[A-Za-z0-9_\\-.]{3,16}$")) {
            ctx.send(Frames.notify("notifications",
                    "handle must be 3-16 chars: letters, digits, _-.", "alert", 3500));
            ctx.send(PROMPT);
            return Transition.None.INSTANCE;
        }
        if (users.findByHandle(handle).isPresent()) {
            ctx.send(Frames.notify("notifications",
                    "that handle is already taken — choose another", "alert", 3500));
            ctx.send(PROMPT);
            return Transition.None.INSTANCE;
        }
        RegisterDraft d = ctx.session().registerDraft();
        if (d == null) d = RegisterDraft.empty();
        ctx.session().setRegisterDraft(d.withHandle(handle));
        ctx.replaceTopAndEnter(Phase.REGISTER_PASSWORD);
        return Transition.None.INSTANCE;
    }

    @Override
    public Transition onCancel(BbsContext ctx) {
        ctx.session().setRegisterDraft(null);
        ctx.replaceTopAndEnter(Phase.LOGIN_HANDLE);
        return Transition.None.INSTANCE;
    }
}
