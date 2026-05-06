package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.documents.DocumentFilter;
import io.aeyer.voidcore.documents.FacetCount;
import io.aeyer.voidcore.instance.InstanceFeature;
import io.aeyer.voidcore.ws.flow.Frames;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.flow.screen.Screen;
import io.aeyer.voidcore.ws.flow.screen.ScreenComponent;
import io.aeyer.voidcore.ws.flow.screen.Transition;
import io.aeyer.voidcore.ws.flow.view.DocumentView;
import io.aeyer.voidcore.ws.protocol.ServerMessage.InputPrompt;
import io.aeyer.voidcore.ws.protocol.ServerMessage.Row;

import java.util.ArrayList;
import java.util.List;

/**
 * Author facet picker (PR-5). Top-N authors by doc count within the
 * current filter, joined to {@code users.handle}. Numbered selection
 * extends the filter with that author and re-enters
 * {@code DOCS_RESULTS}.
 *
 * <p>If the filter already has an author set, the picker is a no-op
 * — pop on enter (single-value facet, can't narrow further).
 */
@ScreenComponent
public class DocsFacetByScreen implements Screen {

    @Override public Phase phase() { return Phase.DOCS_FACET_BY; }
    @Override public String name() { return "docs-facet-by"; }

    @Override
    public List<String> topics(BbsContext ctx) {
        return ScreenFeatureGate.withTopic(List.of(DocumentView.TOPIC));
    }

    @Override
    public Transition onEnter(BbsContext ctx) {
        if (!ScreenFeatureGate.ensureEnabled(ctx, InstanceFeature.INFO_DOCS, "info / docs")) {
            return Transition.None.INSTANCE;
        }
        DocumentFilter cur = DocsCommon.currentFilter(ctx.session());
        if (cur.authorId().isPresent()) {
            ctx.send(Frames.notify("notifications",
                    "author already filtered", "info", 2000));
            ctx.pop();
            return Transition.None.INSTANCE;
        }
        DocumentView docs = ctx.services().documents();
        List<FacetCount.Author> authors = docs.authorFacetCounts(
                cur, ctx.session(), DocsCommon.PICKER_TOP_N);
        if (authors.isEmpty()) {
            ctx.send(Frames.notify("notifications",
                    "no documents in scope", "warn", 2000));
            ctx.pop();
            return Transition.None.INSTANCE;
        }

        ArrayList<Row> rows = new ArrayList<>();
        rows.add(Frames.colored(0,
                "  == " + DocsCommon.facetPath("by-author") + " ==          cd into author",
                "bright_yellow"));
        rows.add(DocsCommon.blank(1));
        int rowN = 2;
        int n = 1;
        int max = Math.min(authors.size(), 9);
        for (int i = 0; i < max; i++) {
            FacetCount.Author a = authors.get(i);
            rows.add(numberedRow(rowN++, n++, a.handle() + "/", a.count()));
        }
        if (authors.size() > 9) {
            rows.add(Frames.colored(rowN++,
                "  (and " + (authors.size() - 9)
                            + " more — use filter expr to jump deeper)", "dark_grey"));
        }
        rows.add(DocsCommon.blank(rowN++));
        rows.add(Frames.row(rowN++,
                Frames.span("  [", "grey"),
                Frames.span(".", "bright_yellow", true),
                Frames.span("] up  [", "grey"),
                Frames.span("Q", "bright_yellow", true),
                Frames.span("] back to menu", "grey")));

        ctx.send(Frames.update("main", 76, rows));
        ctx.send(new InputPrompt("keystroke", "docs:/by-author/", null,
                DocsCommon.numberedKeys(max, ".Q"), null));
        return Transition.None.INSTANCE;
    }

    private static Row numberedRow(int rowN, int n, String handle, long count) {
        return Frames.row(rowN,
                Frames.span("  ", null),
                Frames.span("[" + n + "]", "bright_yellow", true),
                Frames.span(" ", null),
                Frames.span(DocsCommon.padRight(handle, 28), "default"),
                Frames.span(String.valueOf(count), "grey"));
    }

    @Override
    public Transition onKey(BbsContext ctx, String k) {
        if (".".equals(k) || "Q".equals(k)) {
            ctx.pop();
            return Transition.None.INSTANCE;
        }
        if (k.length() != 1 || k.charAt(0) < '1' || k.charAt(0) > '9') {
            return Transition.None.INSTANCE;
        }
        int n = k.charAt(0) - '0';
        DocumentFilter cur = DocsCommon.currentFilter(ctx.session());
        List<FacetCount.Author> authors = ctx.services().documents().authorFacetCounts(
                cur, ctx.session(), DocsCommon.PICKER_TOP_N);
        if (n < 1 || n > Math.min(authors.size(), 9)) return Transition.None.INSTANCE;

        FacetCount.Author picked = authors.get(n - 1);
        DocumentFilter next = cur.withAuthor(picked.userId());
        DocsCommon.writeFilter(ctx.session(), next);
        ctx.session().setDocsResultsPage(0);
        ctx.replaceTopAndEnter(Phase.DOCS_RESULTS);
        return Transition.None.INSTANCE;
    }
}
