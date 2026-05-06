package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.social.ActivityEventRepository;
import io.aeyer.voidcore.ws.flow.Frames;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.flow.screen.Screen;
import io.aeyer.voidcore.ws.flow.screen.ScreenComponent;
import io.aeyer.voidcore.ws.flow.screen.Transition;
import io.aeyer.voidcore.ws.protocol.ServerMessage.InputPrompt;
import io.aeyer.voidcore.ws.protocol.ServerMessage.Row;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * #87 recent activity feed. Lists the last 20 bus events in
 * newest-first order with handle + topic + relative time. Read-only;
 * not interactive (no numbered open) for v1 — most events don't have
 * a meaningful "navigate to" action without per-topic decoding.
 */
@ScreenComponent
public class ActivityFeedScreen implements Screen {

    private static final int LIMIT = 20;

    private final ActivityEventRepository repo;

    public ActivityFeedScreen(ActivityEventRepository repo) {
        this.repo = repo;
    }

    @Override public Phase phase() { return Phase.ACTIVITY_FEED; }
    @Override public String name() { return "activity-feed"; }

    @Override
    public Transition onEnter(BbsContext ctx) {
        if (!ctx.isAuthenticated()) {
            ctx.pop();
            return Transition.None.INSTANCE;
        }
        ctx.persistCurrentScreen("{\"kind\":\"activity_feed\"}");
        List<ActivityEventRepository.Event> events = repo.recent(LIMIT);

        ArrayList<Row> rows = new ArrayList<>();
        rows.add(Frames.colored(0,
                "  == RECENT ACTIVITY ==   last " + events.size(),
                "bright_yellow"));
        rows.add(Frames.blank(1));
        if (events.isEmpty()) {
            rows.add(Frames.colored(2, "  (no recent activity yet)", "dark_grey"));
        } else {
            int rowN = 2;
            for (ActivityEventRepository.Event e : events) {
                String when = relativeWhen(e.emittedAt());
                String actor = e.actorHandle() == null ? "system" : e.actorHandle();
                rows.add(Frames.row(rowN++,
                        Frames.span("  ", null),
                        Frames.span(DocsCommon.padRight(when, 14), "grey"),
                        Frames.span(DocsCommon.padRight(actor, 16), "default"),
                        Frames.span(e.topic(), "bright_cyan")));
            }
        }
        rows.add(Frames.blank(rows.size()));
        rows.add(Frames.row(rows.size(),
                Frames.span("  [", "grey"),
                Frames.span("Q", "bright_yellow", true),
                Frames.span("] back to menu", "grey")));

        ctx.send(Frames.update("main", 76, rows));
        ctx.send(new InputPrompt("keystroke", "activity:", null, "Q", null));
        return Transition.None.INSTANCE;
    }

    @Override
    public Transition onKey(BbsContext ctx, String k) {
        if ("Q".equals(k)) ctx.pop();
        return Transition.None.INSTANCE;
    }

    private static String relativeWhen(OffsetDateTime when) {
        if (when == null) return "?";
        long secs = ChronoUnit.SECONDS.between(when, OffsetDateTime.now());
        if (secs < 60) return secs + "s ago";
        long mins = secs / 60;
        if (mins < 60) return mins + "m ago";
        long hours = mins / 60;
        if (hours < 24) return hours + "h ago";
        long days = hours / 24;
        if (days < 30) return days + "d ago";
        return when.toLocalDate().toString();
    }
}
