package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.social.AchievementRepository;
import io.aeyer.voidcore.ws.flow.Frames;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.flow.screen.Screen;
import io.aeyer.voidcore.ws.flow.screen.ScreenComponent;
import io.aeyer.voidcore.ws.flow.screen.Transition;
import io.aeyer.voidcore.ws.protocol.ServerMessage.InputPrompt;
import io.aeyer.voidcore.ws.protocol.ServerMessage.Row;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Achievements landing — shows totals, the most recently-unlocked
 * handful, and lets the user drill into the BBS-native catalogue or
 * the per-door breakdown. Full lists live on
 * {@link Phase#ACHIEVEMENTS_BBS} and {@link Phase#ACHIEVEMENTS_DOORS}.
 */
@ScreenComponent
public class AchievementsScreen implements Screen {

    private static final int RECENT_LIMIT = 6;

    private final AchievementRepository repo;

    public AchievementsScreen(AchievementRepository repo) {
        this.repo = repo;
    }

    @Override public Phase phase() { return Phase.ACHIEVEMENTS; }
    @Override public String name() { return "achievements"; }

    @Override
    public Transition onEnter(BbsContext ctx) {
        Long uid = ctx.session().userId();
        if (uid == null) {
            ctx.pop();
            return Transition.None.INSTANCE;
        }
        ctx.persistCurrentScreen("{\"kind\":\"achievements\"}");
        // Clear any stale per-door selection so a fresh entry never lands
        // on the wrong door's detail screen if the user types [D] later.
        ctx.session().setSelectedAchievementDoorId(null);

        List<AchievementRepository.AwardedAchievement> mine = repo.awarded(uid);
        List<AchievementRepository.Achievement> all = repo.catalogue();
        int earnedPoints = mine.stream().mapToInt(AchievementRepository.AwardedAchievement::points).sum();
        int totalPoints = all.stream().mapToInt(AchievementRepository.Achievement::points).sum();
        int doorCount = repo.doorIdsWithAchievements().size();
        int bbsCount = (int) all.stream().filter(a -> AchievementRepository.doorIdFromSlug(a.slug()) == null).count();

        ArrayList<Row> rows = new ArrayList<>();
        rows.add(Frames.colored(0,
                "  == ACHIEVEMENTS ==   " + mine.size() + " of " + all.size() +
                        " unlocked   ·   " + earnedPoints + " / " + totalPoints + " pts",
                "bright_yellow"));
        rows.add(Frames.blank(1));
        int rowN = 2;

        rows.add(Frames.colored(rowN++, "  recent unlocks", "bright_cyan"));
        if (mine.isEmpty()) {
            rows.add(Frames.colored(rowN++, "    none yet — go earn some.", "dark_grey"));
        } else {
            int shown = 0;
            for (AchievementRepository.AwardedAchievement a : mine) {
                if (shown >= RECENT_LIMIT) break;
                String when = formatRelative(a.awardedAt());
                String pointsTag = a.points() > 0 ? ("+" + a.points()) : "    ";
                rows.add(Frames.row(rowN++,
                        Frames.span("    ", null),
                        Frames.span("★ ", "bright_yellow"),
                        Frames.span(DocsCommon.padRight(a.name(), 22), "default"),
                        Frames.span(DocsCommon.padRight(pointsTag, 6), "bright_yellow"),
                        Frames.span(DocsCommon.padRight(when, 14), "grey"),
                        Frames.span(a.description(), "grey")));
                shown += 1;
            }
            if (mine.size() > RECENT_LIMIT) {
                rows.add(Frames.colored(rowN++,
                        "    + " + (mine.size() - RECENT_LIMIT) + " more — drill into [B] or [D]",
                        "dark_grey"));
            }
        }

        rows.add(Frames.blank(rowN++));
        rows.add(Frames.row(rowN++,
                Frames.span("  [", "grey"),
                Frames.span("B", "bright_yellow", true),
                Frames.span("] BBS catalogue (" + bbsCount + ")    ", "grey"),
                Frames.span("[", "grey"),
                Frames.span("D", "bright_yellow", true),
                Frames.span("] Door breakdown (" + doorCount + " door" + (doorCount == 1 ? "" : "s") + ")    ", "grey"),
                Frames.span("[", "grey"),
                Frames.span("Q", "bright_yellow", true),
                Frames.span("] back", "grey")));

        ctx.send(Frames.update("main", 76, rows));
        ctx.send(new InputPrompt("keystroke", "achievements:", null, "BDQ", null));
        return Transition.None.INSTANCE;
    }

    @Override
    public Transition onKey(BbsContext ctx, String k) {
        switch (k) {
            case "B" -> ctx.push(Phase.ACHIEVEMENTS_BBS);
            case "D" -> ctx.push(Phase.ACHIEVEMENTS_DOORS);
            case "Q" -> ctx.pop();
        }
        return Transition.None.INSTANCE;
    }

    static String formatRelative(OffsetDateTime ts) {
        if (ts == null) return "—";
        Duration since = Duration.between(ts, OffsetDateTime.now());
        long secs = Math.max(0, since.getSeconds());
        if (secs < 60) return secs + "s ago";
        long mins = secs / 60;
        if (mins < 60) return mins + "m ago";
        long hours = mins / 60;
        if (hours < 48) return hours + "h ago";
        long days = hours / 24;
        if (days < 30) return days + "d ago";
        long months = days / 30;
        if (months < 12) return months + "mo ago";
        return (months / 12) + "y ago";
    }
}
