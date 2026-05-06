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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * BBS-native achievement catalogue, grouped by category. Drill-in
 * from {@link AchievementsScreen} via [B].
 */
@ScreenComponent
public class AchievementsBbsScreen implements Screen {

    private final AchievementRepository repo;

    public AchievementsBbsScreen(AchievementRepository repo) {
        this.repo = repo;
    }

    @Override public Phase phase() { return Phase.ACHIEVEMENTS_BBS; }
    @Override public String name() { return "achievements-bbs"; }

    @Override
    public Transition onEnter(BbsContext ctx) {
        Long uid = ctx.session().userId();
        if (uid == null) {
            ctx.pop();
            return Transition.None.INSTANCE;
        }
        ctx.persistCurrentScreen("{\"kind\":\"achievements_bbs\"}");

        List<AchievementRepository.AwardedAchievement> mine = repo.awarded(uid);
        Set<String> mySlugs = new HashSet<>();
        for (var a : mine) mySlugs.add(a.slug());

        List<AchievementRepository.Achievement> bbs = new ArrayList<>(repo.bbsCatalogue());
        bbs.sort((x, y) -> {
            int c = blankSafe(x.category()).compareToIgnoreCase(blankSafe(y.category()));
            if (c != 0) return c;
            return x.name().compareToIgnoreCase(y.name());
        });

        int unlocked = (int) bbs.stream().filter(a -> mySlugs.contains(a.slug())).count();
        int earned = bbs.stream().filter(a -> mySlugs.contains(a.slug()))
                .mapToInt(AchievementRepository.Achievement::points).sum();
        int total = bbs.stream().mapToInt(AchievementRepository.Achievement::points).sum();

        ArrayList<Row> rows = new ArrayList<>();
        rows.add(Frames.colored(0,
                "  == BBS ACHIEVEMENTS ==   " + unlocked + " of " + bbs.size() +
                        " unlocked   ·   " + earned + " / " + total + " pts",
                "bright_yellow"));
        rows.add(Frames.blank(1));
        int rowN = 2;

        if (bbs.isEmpty()) {
            rows.add(Frames.colored(rowN++, "  catalogue is empty.", "dark_grey"));
        } else {
            String lastCategory = null;
            for (AchievementRepository.Achievement a : bbs) {
                String cat = blankSafe(a.category());
                if (!cat.isEmpty() && !cat.equals(lastCategory)) {
                    rows.add(Frames.colored(rowN++, "  " + cat, "grey"));
                    lastCategory = cat;
                } else if (cat.isEmpty() && lastCategory != null) {
                    lastCategory = null;
                }
                boolean got = mySlugs.contains(a.slug());
                String pointsTag = a.points() > 0 ? ("+" + a.points()) : "    ";
                rows.add(Frames.row(rowN++,
                        Frames.span("    ", null),
                        Frames.span(got ? "★ " : "  ", got ? "bright_yellow" : "dark_grey"),
                        Frames.span(DocsCommon.padRight(a.name(), 22), got ? "default" : "dark_grey"),
                        Frames.span(DocsCommon.padRight(pointsTag, 6), got ? "bright_yellow" : "dark_grey"),
                        Frames.span(a.description(), got ? "grey" : "dark_grey")));
            }
        }
        rows.add(Frames.blank(rowN++));
        rows.add(Frames.row(rowN++,
                Frames.span("  [", "grey"),
                Frames.span("Q", "bright_yellow", true),
                Frames.span("] back", "grey")));

        ctx.send(Frames.update("main", 76, rows));
        ctx.send(new InputPrompt("keystroke", "achievements/bbs:", null, "Q", null));
        return Transition.None.INSTANCE;
    }

    @Override
    public Transition onKey(BbsContext ctx, String k) {
        if ("Q".equals(k)) ctx.pop();
        return Transition.None.INSTANCE;
    }

    private static String blankSafe(String value) {
        return value == null ? "" : value;
    }
}
