package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.auth.UserRepository;
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

/** Sysop · users — list of users; pick one to manage. */
@ScreenComponent
public class SysopUsersScreen implements Screen {

    private final UserRepository users;

    public SysopUsersScreen(UserRepository users) {
        this.users = users;
    }

    @Override public Phase phase() { return Phase.SYSOP_USERS; }
    @Override public String name() { return "sysop-users"; }

    @Override
    public Transition onEnter(BbsContext ctx) {
        if (!ctx.isSysop()) { ctx.pop(); return Transition.None.INSTANCE; }
        ctx.persistCurrentScreen("{\"kind\":\"sysop_users\"}");
        var list = users.listForBoard(9);
        ArrayList<Row> rows = new ArrayList<>();
        rows.add(Frames.colored(0, "  == SYSOP · USERS ==   " + list.size(), "bright_yellow"));
        rows.add(Frames.blank(1));
        rows.add(Frames.colored(2,
                "      handle           location          calls   sysop  banned",
                "dark_grey"));
        int rowN = 3;
        for (int i = 0; i < list.size(); i++) {
            var u = list.get(i);
            String loc = u.location() == null ? "" : ScreenText.truncate(u.location(), 16);
            rows.add(Frames.row(rowN++,
                    Frames.span("  [", "grey"),
                    Frames.span(String.valueOf(i + 1), "bright_yellow", true),
                    Frames.span("] ", "grey"),
                    Frames.span(ScreenText.padRight(u.handle(), 16), "bright"),
                    Frames.span(ScreenText.padRight(loc, 18), "grey"),
                    Frames.span(ScreenText.padLeft(String.valueOf(u.callCount()), 6), "default"),
                    Frames.span("    ", null),
                    Frames.span(u.isSysop() ? " [*] " : "     ",
                            u.isSysop() ? "bright_red" : "grey")));
        }
        rows.add(Frames.blank(rowN++));
        rows.add(Frames.row(rowN,
                Frames.span("  Pick number to manage user, [", "grey"),
                Frames.span("Q", "bright_yellow", true),
                Frames.span("] back", "grey")));
        ctx.send(Frames.update("main", 71, rows));

        StringBuilder valid = new StringBuilder();
        for (int i = 0; i < list.size(); i++) valid.append(i + 1);
        valid.append('Q');
        ctx.send(new InputPrompt("keystroke", "user:", null, valid.toString(), null));
        return Transition.None.INSTANCE;
    }

    @Override
    public Transition onKey(BbsContext ctx, String key) {
        if (!ctx.isSysop()) return Transition.None.INSTANCE;
        if ("Q".equals(key)) { ctx.pop(); return Transition.None.INSTANCE; }
        if (key.length() == 1 && Character.isDigit(key.charAt(0))) {
            int idx = Character.digit(key.charAt(0), 10);
            var list = users.listForBoard(9);
            if (idx >= 1 && idx <= list.size()) {
                ctx.session().setSelectedSysopId(list.get(idx - 1).id());
                ctx.push(Phase.SYSOP_USER);
            }
        }
        return Transition.None.INSTANCE;
    }
}
