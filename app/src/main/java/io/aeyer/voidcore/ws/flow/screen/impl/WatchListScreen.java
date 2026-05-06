package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.social.WatchListRepository;
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
 * #91 watch list screen. Lists users the current session's user is
 * watching. Numbered selection opens that user's faceted-nav page
 * (PR-8 wiring — same surface as {@code ?user/<handle>}). v1
 * doesn't surface an "add/remove" UI; users are added via the
 * future profile-context UX. The list itself is the value: see who
 * you're tracking and jump to their docs.
 */
@ScreenComponent
public class WatchListScreen implements Screen {

    private final WatchListRepository repo;

    public WatchListScreen(WatchListRepository repo) {
        this.repo = repo;
    }

    @Override public Phase phase() { return Phase.WATCH_LIST; }
    @Override public String name() { return "watch-list"; }

    @Override
    public Transition onEnter(BbsContext ctx) {
        Long uid = ctx.session().userId();
        if (uid == null) {
            ctx.pop();
            return Transition.None.INSTANCE;
        }
        ctx.persistCurrentScreen("{\"kind\":\"watch_list\"}");
        List<WatchListRepository.WatchedUser> list = repo.watchedBy(uid);

        ArrayList<Row> rows = new ArrayList<>();
        rows.add(Frames.colored(0,
                "  == WATCH LIST ==   " + list.size() + " watched",
                "bright_yellow"));
        rows.add(Frames.blank(1));
        if (list.isEmpty()) {
            rows.add(Frames.colored(2,
                    "  (nobody yet — add users from their docs view)",
                    "dark_grey"));
        } else {
            int rowN = 2;
            int n = 1;
            int max = Math.min(list.size(), 9);
            for (int i = 0; i < max; i++) {
                WatchListRepository.WatchedUser w = list.get(i);
                rows.add(Frames.row(rowN++,
                        Frames.span("  ", null),
                        Frames.span("[" + n++ + "] ", "bright_yellow", true),
                        Frames.span(DocsCommon.padRight(w.handle(), 18), "default"),
                        Frames.span("watched " + relativeWhen(w.watchedAt()), "grey")));
            }
            if (list.size() > 9) {
                rows.add(Frames.colored(rowN++,
                        "  (and " + (list.size() - 9) + " more — paging lands later)",
                        "dark_grey"));
            }
        }
        rows.add(Frames.blank(rows.size()));
        rows.add(Frames.row(rows.size(),
                Frames.span("  [", "grey"),
                Frames.span("Q", "bright_yellow", true),
                Frames.span("] back to menu", "grey")));

        ctx.send(Frames.update("main", 76, rows));
        ctx.send(new InputPrompt("keystroke", "watch:", null,
                DocsCommon.numberedKeys(Math.min(list.size(), 9), "Q"), null));
        return Transition.None.INSTANCE;
    }

    @Override
    public Transition onKey(BbsContext ctx, String k) {
        if ("Q".equals(k)) {
            ctx.pop();
            return Transition.None.INSTANCE;
        }
        if (k == null || k.length() != 1
                || k.charAt(0) < '1' || k.charAt(0) > '9') {
            return Transition.None.INSTANCE;
        }
        Long uid = ctx.session().userId();
        if (uid == null) return Transition.None.INSTANCE;
        List<WatchListRepository.WatchedUser> list = repo.watchedBy(uid);
        int n = k.charAt(0) - '0';
        if (n < 1 || n > Math.min(list.size(), 9)) return Transition.None.INSTANCE;
        WatchListRepository.WatchedUser picked = list.get(n - 1);
        // Land on that user's faceted-nav results — same surface as
        // ?user/<handle> intent (PR-8).
        io.aeyer.voidcore.documents.DocumentFilter filter =
                io.aeyer.voidcore.documents.DocumentFilter.empty()
                        .withAuthor(picked.userId());
        ctx.session().setDocsFilter(filter.serialise());
        ctx.session().setDocsResultsPage(0);
        ctx.session().setDocsResultsSort(null);
        ctx.push(Phase.DOCS_RESULTS);
        return Transition.None.INSTANCE;
    }

    private static String relativeWhen(OffsetDateTime when) {
        if (when == null) return "?";
        long minutes = ChronoUnit.MINUTES.between(when, OffsetDateTime.now());
        if (minutes < 60) return "just now";
        long hours = minutes / 60;
        if (hours < 24) return hours + "h ago";
        long days = hours / 24;
        if (days < 30) return days + "d ago";
        return when.toLocalDate().toString();
    }
}
