package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.auth.UserRepository;
import io.aeyer.voidcore.ws.flow.Frames;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.flow.screen.Screen;
import io.aeyer.voidcore.ws.flow.screen.ScreenComponent;
import io.aeyer.voidcore.ws.flow.screen.Transition;
import io.aeyer.voidcore.ws.protocol.ServerMessage.InputPrompt;

/** Sysop · ban reason capture. */
@ScreenComponent
public class SysopUserBanReasonScreen implements Screen {

    private static final InputPrompt PROMPT = new InputPrompt(
            "line", "ban reason (visible on next login attempt):", 256, null, null);

    private final UserRepository users;

    public SysopUserBanReasonScreen(UserRepository users) {
        this.users = users;
    }

    @Override public Phase phase() { return Phase.SYSOP_USER_BAN_REASON; }
    @Override public String name() { return "sysop-user-ban-reason"; }

    @Override
    public Transition onEnter(BbsContext ctx) {
        if (!ctx.isSysop()) { ctx.pop(); return Transition.None.INSTANCE; }
        ctx.send(PROMPT);
        return Transition.None.INSTANCE;
    }

    @Override
    public Transition onLine(BbsContext ctx, String text) {
        if (!ctx.isSysop()) return Transition.None.INSTANCE;
        Long target = ctx.session().selectedSysopId();
        if (target == null) { ctx.pop(); return Transition.None.INSTANCE; }
        String reason = text == null || text.isEmpty() ? "banned by sysop" : text;
        users.setBanned(target, true, reason);
        ctx.audit("ban_user",
                ctx.services().json().createObjectNode()
                        .put("user_id", target).put("reason", reason));
        ctx.send(Frames.notify("notifications",
                "user banned: " + reason, "warn", 3500));
        ctx.pop();
        return Transition.None.INSTANCE;
    }

    @Override
    public Transition onCancel(BbsContext ctx) {
        ctx.pop();
        return Transition.None.INSTANCE;
    }
}
