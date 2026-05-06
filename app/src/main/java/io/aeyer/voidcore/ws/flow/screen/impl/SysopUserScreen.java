package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.auth.UserRepository;
import io.aeyer.voidcore.auth.UserRepository.UserRow;
import io.aeyer.voidcore.ws.flow.Frames;
import io.aeyer.voidcore.ws.flow.layout.Element;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.flow.screen.ScreenAppComponent;
import io.aeyer.voidcore.ws.flow.screen.form.FormField;
import io.aeyer.voidcore.ws.flow.screen.form.MenuAction;
import io.aeyer.voidcore.ws.flow.screen.form.MenuFormApp;

import java.util.ArrayList;
import java.util.List;

@ScreenAppComponent
public class SysopUserScreen extends MenuFormApp<UserRow> {

    private final UserRepository users;

    public SysopUserScreen(UserRepository users) { this.users = users; }

    @Override public Phase phase() { return Phase.SYSOP_USER; }
    @Override public String name() { return "sysop-user"; }

    @Override
    protected String appKey(BbsContext ctx) {
        Long id = ctx.session().selectedSysopId();
        return id == null ? "sysop-user:none" : "sysop-user:" + id;
    }

    @Override
    protected UserRow loadState(BbsContext ctx) {
        if (!ctx.isSysop()) return null;
        Long id = ctx.session().selectedSysopId();
        if (id == null) return null;
        return users.findById(id).orElse(null);
    }

    @Override
    protected String bannerLabel(BbsContext ctx, UserRow u) {
        return "SYSOP/USER · " + u.handle();
    }

    @Override
    protected Element headerElement(BbsContext ctx, UserRow u) {
        return new Element.VStack(List.of(
            new Element.Header("SYSOP · USER", null),
            new Element.Text("  handle : " + u.handle()),
            new Element.Text("  sysop  : " + (u.isSysop() ? "yes" : "no"),
                u.isSysop() ? "bright_red" : "default"),
            new Element.Text("  banned : " + (u.isBanned() ? "yes" : "no"),
                u.isBanned() ? "bright_red" : "default")
        ), 0);
    }

    @Override
    protected List<FormField<UserRow>> fields(BbsContext ctx, UserRow u) {
        return List.of();   // action-only
    }

    @Override
    protected void onQuit(BbsContext ctx) {
        ctx.session().setSelectedSysopId(null);
    }

    @Override
    protected List<MenuAction<UserRow>> menuActions(BbsContext ctx, UserRow u) {
        List<MenuAction<UserRow>> out = new ArrayList<>();
        if (u.isBanned()) {
            out.add(new MenuAction<>("U", "unban", null,
                (c, ur) -> {
                    users.setBanned(ur.id(), false, null);
                    c.audit("unban_user",
                        c.services().json().createObjectNode().put("user_id", ur.id()));
                    c.send(Frames.notify("notifications", "user unbanned", "info", 2500));
                    repaintNow(c);
                }));
        } else {
            out.add(new MenuAction<>("B", "ban", "bright_red",
                (c, ur) -> c.push(Phase.SYSOP_USER_BAN_REASON)));
        }
        out.add(new MenuAction<>("R", "reset password", null,
            (c, ur) -> c.push(Phase.SYSOP_USER_RESET_PW)));
        out.add(new MenuAction<>("L", "roles", null,
            (c, ur) -> c.push(Phase.SYSOP_USER_ROLES)));
        out.add(new MenuAction<>("C", "chat room access", null,
            (c, ur) -> c.push(Phase.SYSOP_USER_CHAT_ROOMS)));
        out.add(new MenuAction<>("P", "permissions", null,
            (c, ur) -> c.push(Phase.SYSOP_USER_PERMISSIONS)));
        return out;
    }
}
