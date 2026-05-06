package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.auth.RoleRepository;
import io.aeyer.voidcore.ws.flow.Frames;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.flow.screen.Screen;
import io.aeyer.voidcore.ws.flow.screen.ScreenComponent;
import io.aeyer.voidcore.ws.flow.screen.ScreenText;
import io.aeyer.voidcore.ws.flow.screen.Transition;
import io.aeyer.voidcore.ws.protocol.ServerMessage.InputPrompt;
import io.aeyer.voidcore.ws.protocol.ServerMessage.Row;

import java.util.ArrayList;
import java.util.List;

@ScreenComponent
public class SysopRolesScreen implements Screen {

    private final RoleRepository roles;

    public SysopRolesScreen(RoleRepository roles) {
        this.roles = roles;
    }

    @Override public Phase phase() { return Phase.SYSOP_ROLES; }
    @Override public String name() { return "sysop-roles"; }

    @Override
    public Transition onEnter(BbsContext ctx) {
        if (!ctx.isSysop()) { ctx.pop(); return Transition.None.INSTANCE; }
        ctx.persistCurrentScreen("{\"kind\":\"sysop_roles\"}");
        var list = roles.listAllRoles();
        ArrayList<Row> rows = new ArrayList<>();
        rows.add(Frames.colored(0, "  == SYSOP · ROLES ==   " + list.size(), "bright_yellow"));
        rows.add(Frames.blank(1));
        int rowN = 2;
        if (list.isEmpty()) {
            rows.add(Frames.colored(rowN++, "  (no roles yet)", "dark_grey"));
        } else {
            for (int i = 0; i < list.size(); i++) {
                var role = list.get(i);
                String desc = role.description() == null ? "" : ScreenText.truncate(role.description(), 40);
                rows.add(Frames.row(rowN++,
                        Frames.span("  [", "grey"),
                        Frames.span(String.valueOf(i + 1), "bright_yellow", true),
                        Frames.span("] ", "grey"),
                        Frames.span(ScreenText.padRight(role.name(), 18), "bright_cyan", true),
                        Frames.span(desc, "default")));
            }
        }
        rows.add(Frames.blank(rowN++));
        String adminPolicy = RolePolicySummary.compactLine("ADMIN");
        String moderatorPolicy = RolePolicySummary.compactLine("MODERATOR");
        if (!adminPolicy.isBlank()) {
            rows.add(Frames.colored(rowN++, "  " + ScreenText.truncate(adminPolicy, 72), "dark_grey"));
        }
        if (!moderatorPolicy.isBlank()) {
            rows.add(Frames.colored(rowN++, "  " + ScreenText.truncate(moderatorPolicy, 72), "dark_grey"));
        }
        rows.add(Frames.blank(rowN++));
        rows.add(Frames.row(rowN,
                Frames.span("  [", "grey"),
                Frames.span("N", "bright_yellow", true),
                Frames.span("] new role   pick number to manage   [", "grey"),
                Frames.span("Q", "bright_yellow", true),
                Frames.span("] back", "grey")));
        ctx.send(Frames.update("main", 75, rows));

        StringBuilder valid = new StringBuilder("NQ");
        for (int i = 0; i < list.size(); i++) valid.append(i + 1);
        ctx.send(new InputPrompt("keystroke", "role:", null, valid.toString(), null));
        return Transition.None.INSTANCE;
    }

    @Override
    public Transition onKey(BbsContext ctx, String key) {
        if (!ctx.isSysop()) return Transition.None.INSTANCE;
        switch (key) {
            case "Q" -> {
                ctx.session().setSelectedSysopId(null);
                ctx.pop();
            }
            case "N" -> {
                ctx.session().setSelectedSysopId(null);
                ctx.push(Phase.SYSOP_ROLE_NEW);
            }
            default -> {
                if (key.length() == 1 && Character.isDigit(key.charAt(0))) {
                    int idx = Character.digit(key.charAt(0), 10);
                    var list = roles.listAllRoles();
                    if (idx >= 1 && idx <= list.size()) {
                        ctx.session().setSelectedSysopId(list.get(idx - 1).id());
                        ctx.push(Phase.SYSOP_ROLE);
                    }
                }
            }
        }
        return Transition.None.INSTANCE;
    }
}
