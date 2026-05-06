package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.doors.DoorRuntimeService;
import io.aeyer.voidcore.doors.DoorSummary;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Door picker for the achievements drill-down. Shows one numbered
 * entry per door that has at least one achievement registered, plus
 * the user's unlocked count and points for that door. Press a digit
 * to push {@link Phase#ACHIEVEMENTS_DOOR} for that door.
 */
@ScreenComponent
public class AchievementsDoorsListScreen implements Screen {

    private static final int MAX_DOORS = 9;

    private final AchievementRepository repo;
    private final DoorRuntimeService doors;

    public AchievementsDoorsListScreen(AchievementRepository repo, DoorRuntimeService doors) {
        this.repo = repo;
        this.doors = doors;
    }

    @Override public Phase phase() { return Phase.ACHIEVEMENTS_DOORS; }
    @Override public String name() { return "achievements-doors"; }

    /** Cached per-onEnter so onKey can resolve the digit without re-querying. */
    private volatile List<String> doorIds = List.of();

    @Override
    public Transition onEnter(BbsContext ctx) {
        Long uid = ctx.session().userId();
        if (uid == null) {
            ctx.pop();
            return Transition.None.INSTANCE;
        }
        ctx.persistCurrentScreen("{\"kind\":\"achievements_doors\"}");

        List<AchievementRepository.AwardedAchievement> mine = repo.awarded(uid);
        Set<String> mySlugs = new HashSet<>();
        for (var a : mine) mySlugs.add(a.slug());

        Map<String, String> nameById = new HashMap<>();
        for (DoorSummary s : doors.listConnectedDoors()) {
            nameById.put(s.doorId(), s.name());
        }

        List<String> ids = repo.doorIdsWithAchievements();
        if (ids.size() > MAX_DOORS) ids = ids.subList(0, MAX_DOORS);
        this.doorIds = ids;

        ArrayList<Row> rows = new ArrayList<>();
        rows.add(Frames.colored(0, "  == DOOR ACHIEVEMENTS ==", "bright_yellow"));
        rows.add(Frames.blank(1));
        int rowN = 2;

        if (ids.isEmpty()) {
            rows.add(Frames.colored(rowN++, "  no door has registered an achievement yet.", "dark_grey"));
        } else {
            for (int i = 0; i < ids.size(); i++) {
                String id = ids.get(i);
                List<AchievementRepository.Achievement> cat = repo.doorCatalogue(id);
                int total = cat.size();
                int got = (int) cat.stream().filter(a -> mySlugs.contains(a.slug())).count();
                int totalPts = cat.stream().mapToInt(AchievementRepository.Achievement::points).sum();
                int gotPts = cat.stream().filter(a -> mySlugs.contains(a.slug()))
                        .mapToInt(AchievementRepository.Achievement::points).sum();
                String name = nameById.getOrDefault(id, id);
                String summary = got + "/" + total + " · " + gotPts + "/" + totalPts + "pts";
                rows.add(Frames.row(rowN++,
                        Frames.span("  [", "grey"),
                        Frames.span(String.valueOf(i + 1), "bright_yellow", true),
                        Frames.span("] ", "grey"),
                        Frames.span(DocsCommon.padRight(name, 24), "default"),
                        Frames.span(DocsCommon.padRight(summary, 22), "bright_cyan"),
                        Frames.span(id, "dark_grey")));
            }
        }
        rows.add(Frames.blank(rowN++));
        rows.add(Frames.row(rowN++,
                Frames.span("  [", "grey"),
                Frames.span("Q", "bright_yellow", true),
                Frames.span("] back", "grey")));

        StringBuilder validKeys = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) validKeys.append(i + 1);
        validKeys.append('Q');

        ctx.send(Frames.update("main", 76, rows));
        ctx.send(new InputPrompt("keystroke", "achievements/doors:", null, validKeys.toString(), null));
        return Transition.None.INSTANCE;
    }

    @Override
    public Transition onKey(BbsContext ctx, String k) {
        if ("Q".equals(k)) {
            ctx.pop();
            return Transition.None.INSTANCE;
        }
        if (k != null && k.length() == 1 && Character.isDigit(k.charAt(0))) {
            int idx = k.charAt(0) - '1';
            if (idx >= 0 && idx < doorIds.size()) {
                ctx.session().setSelectedAchievementDoorId(doorIds.get(idx));
                ctx.push(Phase.ACHIEVEMENTS_DOOR);
            }
        }
        return Transition.None.INSTANCE;
    }
}
