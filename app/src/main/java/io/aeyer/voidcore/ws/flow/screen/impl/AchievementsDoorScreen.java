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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Catalogue for a single door, keyed by
 * {@link io.aeyer.voidcore.ws.VoidCoreSession#selectedAchievementDoorId()}.
 * Pushed from {@link AchievementsDoorsListScreen}.
 */
@ScreenComponent
public class AchievementsDoorScreen implements Screen {

    private final AchievementRepository repo;
    private final DoorRuntimeService doors;

    public AchievementsDoorScreen(AchievementRepository repo, DoorRuntimeService doors) {
        this.repo = repo;
        this.doors = doors;
    }

    @Override public Phase phase() { return Phase.ACHIEVEMENTS_DOOR; }
    @Override public String name() { return "achievements-door"; }

    @Override
    public Transition onEnter(BbsContext ctx) {
        Long uid = ctx.session().userId();
        if (uid == null) {
            ctx.pop();
            return Transition.None.INSTANCE;
        }
        String doorId = ctx.session().selectedAchievementDoorId();
        if (doorId == null || doorId.isBlank()) {
            ctx.pop();
            return Transition.None.INSTANCE;
        }
        ctx.persistCurrentScreen("{\"kind\":\"achievements_door\",\"door_id\":\"" + doorId + "\"}");

        List<AchievementRepository.AwardedAchievement> mine = repo.awarded(uid);
        Set<String> mySlugs = new HashSet<>();
        for (var a : mine) mySlugs.add(a.slug());

        List<AchievementRepository.Achievement> cat = new ArrayList<>(repo.doorCatalogue(doorId));
        cat.sort((x, y) -> {
            int c = blankSafe(x.category()).compareToIgnoreCase(blankSafe(y.category()));
            if (c != 0) return c;
            return x.name().compareToIgnoreCase(y.name());
        });

        String displayName = doorId;
        for (DoorSummary s : doors.listConnectedDoors()) {
            if (doorId.equals(s.doorId())) {
                displayName = s.name();
                break;
            }
        }

        int unlocked = (int) cat.stream().filter(a -> mySlugs.contains(a.slug())).count();
        int earned = cat.stream().filter(a -> mySlugs.contains(a.slug()))
                .mapToInt(AchievementRepository.Achievement::points).sum();
        int total = cat.stream().mapToInt(AchievementRepository.Achievement::points).sum();

        ArrayList<Row> rows = new ArrayList<>();
        rows.add(Frames.colored(0,
                "  == " + displayName.toUpperCase() + " ==   " + unlocked + " of " + cat.size() +
                        " unlocked   ·   " + earned + " / " + total + " pts",
                "bright_yellow"));
        rows.add(Frames.colored(1, "  door: " + doorId, "dark_grey"));
        rows.add(Frames.blank(2));
        int rowN = 3;

        if (cat.isEmpty()) {
            rows.add(Frames.colored(rowN++, "  this door has no achievements registered yet.", "dark_grey"));
        } else {
            String lastCategory = null;
            for (AchievementRepository.Achievement a : cat) {
                String catName = blankSafe(a.category());
                if (!catName.isEmpty() && !catName.equals(lastCategory)) {
                    rows.add(Frames.colored(rowN++, "  " + catName, "grey"));
                    lastCategory = catName;
                } else if (catName.isEmpty() && lastCategory != null) {
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
        ctx.send(new InputPrompt("keystroke", "achievements/" + doorId + ":", null, "Q", null));
        return Transition.None.INSTANCE;
    }

    @Override
    public Transition onKey(BbsContext ctx, String k) {
        if ("Q".equals(k)) {
            ctx.session().setSelectedAchievementDoorId(null);
            ctx.pop();
        }
        return Transition.None.INSTANCE;
    }

    private static String blankSafe(String value) {
        return value == null ? "" : value;
    }
}
