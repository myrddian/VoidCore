package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.auth.PasswordHasher;
import io.aeyer.voidcore.auth.UserRepository;
import io.aeyer.voidcore.ws.flow.Frames;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.flow.screen.Screen;
import io.aeyer.voidcore.ws.flow.screen.ScreenComponent;
import io.aeyer.voidcore.ws.flow.screen.Transition;
import io.aeyer.voidcore.ws.protocol.ServerMessage.InputPrompt;

/** Sysop · reset password capture. */
@ScreenComponent
public class SysopUserResetPwScreen implements Screen {

    private static final InputPrompt PROMPT = new InputPrompt(
            "password", "new password (min 8):", 4096, null, null);

    private final UserRepository users;
    private final PasswordHasher hasher;

    public SysopUserResetPwScreen(UserRepository users, PasswordHasher hasher) {
        this.users = users;
        this.hasher = hasher;
    }

    @Override public Phase phase() { return Phase.SYSOP_USER_RESET_PW; }
    @Override public String name() { return "sysop-user-reset-pw"; }

    @Override
    public Transition onEnter(BbsContext ctx) {
        if (!ctx.isSysop()) { ctx.pop(); return Transition.None.INSTANCE; }
        ctx.send(PROMPT);
        return Transition.None.INSTANCE;
    }

    @Override
    public Transition onLine(BbsContext ctx, String pw) {
        if (!ctx.isSysop()) return Transition.None.INSTANCE;
        Long target = ctx.session().selectedSysopId();
        if (target == null) { ctx.pop(); return Transition.None.INSTANCE; }
        if (pw == null || pw.length() < 8) {
            ctx.send(Frames.notify("notifications",
                    "password must be at least 8 characters", "alert", 3000));
            ctx.send(PROMPT);
            return Transition.None.INSTANCE;
        }
        users.updatePasswordHash(target, hasher.hash(pw));
        ctx.audit("reset_password",
                ctx.services().json().createObjectNode().put("user_id", target));
        ctx.send(Frames.notify("notifications", "password reset", "info", 2500));
        ctx.pop();
        return Transition.None.INSTANCE;
    }

    @Override
    public Transition onCancel(BbsContext ctx) {
        ctx.pop();
        return Transition.None.INSTANCE;
    }
}
