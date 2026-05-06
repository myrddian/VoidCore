package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.auth.RoleRepository;
import io.aeyer.voidcore.auth.UserRepository;
import io.aeyer.voidcore.ws.flow.Frames;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.flow.screen.Screen;
import io.aeyer.voidcore.ws.flow.screen.ScreenComponent;
import io.aeyer.voidcore.ws.flow.screen.Transition;
import io.aeyer.voidcore.ws.protocol.ServerMessage.InputPrompt;
import io.aeyer.voidcore.ws.protocol.ServerMessage.Row;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@ScreenComponent
public class SysopUserRolesScreen implements Screen {

    private final UserRepository users;
    private final RoleRepository roles;

    public SysopUserRolesScreen(UserRepository users, RoleRepository roles) {
        this.users = users;
        this.roles = roles;
    }

    @Override public Phase phase() { return Phase.SYSOP_USER_ROLES; }
    @Override public String name() { return "sysop-user-roles"; }

    @Override
    public Transition onEnter(BbsContext ctx) {
        if (!ctx.isSysop()) { ctx.pop(); return Transition.None.INSTANCE; }
        Long userId = ctx.session().selectedSysopId();
        if (userId == null) { ctx.pop(); return Transition.None.INSTANCE; }
        var user = users.findById(userId).orElse(null);
        if (user == null) { ctx.pop(); return Transition.None.INSTANCE; }
        Set<Long> assigned = roles.rolesForUser(userId).stream().map(RoleRepository.RoleRow::id).collect(Collectors.toSet());
        var all = roles.listAllRoles();

        ArrayList<Row> rows = new ArrayList<>();
        rows.add(Frames.colored(0, "  == USER ROLES · " + user.handle().toUpperCase() + " ==", "bright_yellow"));
        rows.add(Frames.blank(1));
        int rowN = 2;
        if (all.isEmpty()) {
            rows.add(Frames.colored(rowN++, "  (no roles defined yet)", "dark_grey"));
        } else {
            for (int i = 0; i < all.size(); i++) {
                var role = all.get(i);
                boolean on = assigned.contains(role.id());
                rows.add(Frames.row(rowN++,
                        Frames.span("  [", "grey"),
                        Frames.span(String.valueOf(i + 1), "bright_yellow", true),
                        Frames.span("] ", "grey"),
                        Frames.span(on ? "[*] " : "[ ] ", on ? "bright_green" : "grey"),
                        Frames.span(role.name(), "bright_cyan", true),
                        Frames.span(role.description() == null ? "" : "  " + role.description(), "default")));
            }
        }
        rows.add(Frames.blank(rowN++));
        rows.add(Frames.row(rowN,
                Frames.span("  Pick role number to assign / remove   [", "grey"),
                Frames.span("Q", "bright_yellow", true),
                Frames.span("] back", "grey")));
        ctx.send(Frames.update("main", 76, rows));

        StringBuilder valid = new StringBuilder("Q");
        for (int i = 0; i < all.size(); i++) valid.append(i + 1);
        ctx.send(new InputPrompt("keystroke", "user role:", null, valid.toString(), null));
        return Transition.None.INSTANCE;
    }

    @Override
    public Transition onKey(BbsContext ctx, String key) {
        if (!ctx.isSysop()) return Transition.None.INSTANCE;
        if ("Q".equals(key)) { ctx.pop(); return Transition.None.INSTANCE; }
        Long userId = ctx.session().selectedSysopId();
        if (userId == null) return Transition.None.INSTANCE;
        if (key.length() == 1 && Character.isDigit(key.charAt(0))) {
            int idx = Character.digit(key.charAt(0), 10);
            var all = roles.listAllRoles();
            if (idx < 1 || idx > all.size()) return Transition.None.INSTANCE;
            var role = all.get(idx - 1);
            Set<Long> assigned = roles.rolesForUser(userId).stream().map(RoleRepository.RoleRow::id).collect(Collectors.toSet());
            boolean has = assigned.contains(role.id());
            if (has) {
                roles.removeRole(userId, role.id());
            } else {
                roles.assignRole(userId, role.id());
            }
            ctx.audit(has ? "remove_user_role" : "assign_user_role",
                    ctx.services().json().createObjectNode()
                            .put("user_id", userId)
                            .put("role_id", role.id())
                            .put("role_name", role.name()));
            ctx.send(Frames.notify("notifications",
                    (has ? "removed " : "assigned ") + role.name(),
                    has ? "warn" : "info", 2500));
            onEnter(ctx);
        }
        return Transition.None.INSTANCE;
    }
}
