package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.auth.UserRepository;
import io.aeyer.voidcore.presence.LastCallerRepository;
import io.aeyer.voidcore.presence.PresenceService;
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

/**
 * Read-only "info" surface — variant-driven. The three U/L/W
 * sub-screens (user list, last callers, who's online) all share
 * this single phase and the same [Q] back behaviour; the variant
 * label rides {@link io.aeyer.voidcore.ws.VoidCoreSession#infoVariant()},
 * set by {@code MenuScreen.onKey} before the push.
 *
 * <p>v1.4 PR-A: extracted as a Screen.
 *
 * <p>v1.4 PR-B step 22: rendering moved here. The three paints
 * stay distinct (different headers, columns, repos) but live
 * together — they're trivially small, share the [Q] / pop
 * scaffolding, and don't justify three separate phases.
 */
@ScreenComponent
public class InfoViewScreen implements Screen {

    public static final String VARIANT_USERS = "users";
    public static final String VARIANT_LAST_CALLERS = "last_callers";
    public static final String VARIANT_WHOS_ONLINE = "whos_online";

    private final UserRepository users;
    private final LastCallerRepository lastCallers;
    private final PresenceService presence;

    public InfoViewScreen(UserRepository users,
                          LastCallerRepository lastCallers,
                          PresenceService presence) {
        this.users = users;
        this.lastCallers = lastCallers;
        this.presence = presence;
    }

    @Override public Phase phase() { return Phase.INFO_VIEW; }
    @Override public String name() { return "info-view"; }

    @Override
    public Transition onEnter(BbsContext ctx) {
        String variant = ctx.session().infoVariant();
        if (variant == null) variant = VARIANT_USERS;
        switch (variant) {
            case VARIANT_LAST_CALLERS -> paintLastCallers(ctx);
            case VARIANT_WHOS_ONLINE -> paintWhosOnline(ctx);
            default -> paintUsers(ctx);
        }
        return Transition.None.INSTANCE;
    }

    @Override
    public Transition onKey(BbsContext ctx, String key) {
        if ("Q".equals(key)) {
            ctx.session().setInfoVariant(null);
            ctx.pop();
        }
        return Transition.None.INSTANCE;
    }

    private void paintUsers(BbsContext ctx) {
        ctx.persistCurrentScreen("{\"kind\":\"users\"}");
        var list = users.listForBoard(30);
        ArrayList<Row> rows = new ArrayList<>();
        rows.add(Frames.colored(0, "  == USER LIST ==   " + list.size() + " registered",
                "bright_yellow"));
        rows.add(Frames.blank(1));
        rows.add(Frames.colored(2,
                "      handle           location          calls   posts   last call",
                "dark_grey"));
        int rowN = 3;
        for (var u : list) {
            String loc = u.location() == null ? "" : ScreenText.truncate(u.location(), 16);
            String last = u.lastCallAt() == null ? "—"
                    : u.lastCallAt().toLocalDate().toString();
            rows.add(Frames.row(rowN++,
                    Frames.span("  ", null),
                    Frames.span(u.isSysop() ? "[*] " : "    ",
                            u.isSysop() ? "bright_red" : "grey"),
                    Frames.span(ScreenText.padRight(u.handle(), 16), "bright"),
                    Frames.span(ScreenText.padRight(loc, 18), "grey"),
                    Frames.span(ScreenText.padLeft(String.valueOf(u.callCount()), 6), "default"),
                    Frames.span("  ", null),
                    Frames.span(ScreenText.padLeft(String.valueOf(u.postCount()), 5), "default"),
                    Frames.span("   ", null),
                    Frames.span(last, "grey")));
        }
        rows.add(Frames.blank(rowN++));
        rows.add(backRow(rowN));
        ctx.send(Frames.update("main", 30, rows));
        ctx.send(new InputPrompt("keystroke", "key:", null, "Q", null));
    }

    private void paintLastCallers(BbsContext ctx) {
        ctx.persistCurrentScreen("{\"kind\":\"last_callers\"}");
        var list = lastCallers.recent(20);
        ArrayList<Row> rows = new ArrayList<>();
        rows.add(Frames.colored(0, "  == LAST CALLERS ==", "bright_yellow"));
        rows.add(Frames.blank(1));
        rows.add(Frames.colored(2,
                "      handle           location          when",
                "dark_grey"));
        int rowN = 3;
        for (var c : list) {
            String loc = c.location() == null ? "" : ScreenText.truncate(c.location(), 16);
            String when = c.at() == null ? "—" : c.at().toString().substring(0, 16);
            rows.add(Frames.row(rowN++,
                    Frames.span("      ", null),
                    Frames.span(ScreenText.padRight(c.handle(), 16), "bright"),
                    Frames.span(ScreenText.padRight(loc, 18), "grey"),
                    Frames.span(when, "dark_grey")));
        }
        if (list.isEmpty()) {
            rows.add(Frames.colored(rowN++, "      (no callers recorded yet)", "dark_grey"));
        }
        rows.add(Frames.blank(rowN++));
        rows.add(backRow(rowN));
        ctx.send(Frames.update("main", 31, rows));
        ctx.send(new InputPrompt("keystroke", "key:", null, "Q", null));
    }

    private void paintWhosOnline(BbsContext ctx) {
        ctx.persistCurrentScreen("{\"kind\":\"whos_online\"}");
        var list = presence.activeNow();
        ArrayList<Row> rows = new ArrayList<>();
        rows.add(Frames.colored(0,
                "  == WHO'S ONLINE ==   " + list.size() + " active",
                "bright_yellow"));
        rows.add(Frames.blank(1));
        // #86: enriched header — node, handle, "online for" duration,
        // sysop badge already in place. Replaces the previous absolute
        // timestamp with a relative duration (more useful at-a-glance).
        rows.add(Frames.colored(2,
                "      node   handle             online for",
                "dark_grey"));
        int rowN = 3;
        java.time.Instant now = java.time.Instant.now();
        for (var p : list) {
            rows.add(Frames.row(rowN++,
                    Frames.span("      ", null),
                    Frames.span(ScreenText.padLeft(String.format("%02d", p.nodeNumber()), 4), "bright_green"),
                    Frames.span("   ", null),
                    Frames.span(p.isSysop() ? "[*] " : "    ",
                            p.isSysop() ? "bright_red" : "grey"),
                    Frames.span(ScreenText.padRight(p.handle(), 18), "bright"),
                    Frames.span(formatOnlineDuration(p.joinedAt(), now), "grey")));
        }
        rows.add(Frames.blank(rowN++));
        rows.add(backRow(rowN));
        ctx.send(Frames.update("main", 32, rows));
        ctx.send(new InputPrompt("keystroke", "key:", null, "Q", null));
    }

    /**
     * #86: human-friendly online-for duration. {@code <1m} for fresh
     * sessions, then minutes / hours / days as appropriate.
     */
    private static String formatOnlineDuration(java.time.Instant joinedAt,
                                               java.time.Instant now) {
        long secs = java.time.Duration.between(joinedAt, now).getSeconds();
        if (secs < 60) return "< 1m";
        long mins = secs / 60;
        if (mins < 60) return mins + "m";
        long hours = mins / 60;
        if (hours < 24) return hours + "h " + (mins % 60) + "m";
        long days = hours / 24;
        return days + "d " + (hours % 24) + "h";
    }

    private static Row backRow(int rowN) {
        return Frames.row(rowN,
                Frames.span("  [", "grey"),
                Frames.span("Q", "bright_yellow", true),
                Frames.span("] back to menu", "grey"));
    }
}
