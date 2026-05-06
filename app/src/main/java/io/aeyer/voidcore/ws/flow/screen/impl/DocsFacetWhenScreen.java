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
 * Year facet picker (PR-5). v1 shows year-only buckets — the spec
 * also allows year+month but the recursive picker that would imply
 * adds UI complexity not needed for v1's modest doc count. Month
 * narrowing lands with the search PR (PR-6) which already needs
 * additional input modes.
 *
 * <p>Numbered selection extends the filter with the year and
 * re-enters {@code DOCS_RESULTS}. Self-pops if the filter already
 * has a year (single-value facet).
 */
@ScreenComponent
public class DocsFacetWhenScreen implements Screen {

    @Override public Phase phase() { return Phase.DOCS_FACET_WHEN; }
    @Override public String name() { return "docs-facet-when"; }

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
        if (cur.year().isPresent()) {
            ctx.send(Frames.notify("notifications",
                    "year already filtered", "info", 2000));
            ctx.pop();
            return Transition.None.INSTANCE;
        }
        DocumentView docs = ctx.services().documents();
        List<FacetCount.Year> years = docs.whenFacetCounts(cur, ctx.session());
        if (years.isEmpty()) {
            ctx.send(Frames.notify("notifications",
                    "no documents in scope", "warn", 2000));
            ctx.pop();
            return Transition.None.INSTANCE;
        }

        ArrayList<Row> rows = new ArrayList<>();
        rows.add(Frames.colored(0,
                "  == " + DocsCommon.facetPath("by-year") + " ==            cd into year",
                "bright_yellow"));
        rows.add(DocsCommon.blank(1));
        int rowN = 2;
        int n = 1;
        int max = Math.min(years.size(), 9);
        for (int i = 0; i < max; i++) {
            FacetCount.Year y = years.get(i);
            rows.add(numberedRow(rowN++, n++, y.year() + "/", y.count()));
        }
        if (years.size() > 9) {
            rows.add(Frames.colored(rowN++,
                    "  (and " + (years.size() - 9)
                            + " more years — use filter expr to jump deeper)",
                    "dark_grey"));
        }
        rows.add(DocsCommon.blank(rowN++));
        rows.add(Frames.row(rowN++,
                Frames.span("  [", "grey"),
                Frames.span(".", "bright_yellow", true),
                Frames.span("] up  [", "grey"),
                Frames.span("Q", "bright_yellow", true),
                Frames.span("] back to menu", "grey")));

        ctx.send(Frames.update("main", 76, rows));
        ctx.send(new InputPrompt("keystroke", "docs:/by-year/", null,
                DocsCommon.numberedKeys(max, ".Q"), null));
        return Transition.None.INSTANCE;
    }

    private static Row numberedRow(int rowN, int n, String year, long count) {
        return Frames.row(rowN,
                Frames.span("  ", null),
                Frames.span("[" + n + "]", "bright_yellow", true),
                Frames.span(" ", null),
                Frames.span(DocsCommon.padRight(year, 28), "default"),
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
        List<FacetCount.Year> years = ctx.services().documents().whenFacetCounts(
                cur, ctx.session());
        if (n < 1 || n > Math.min(years.size(), 9)) return Transition.None.INSTANCE;

        FacetCount.Year picked = years.get(n - 1);
        DocumentFilter next = cur.withYear(picked.year());
        DocsCommon.writeFilter(ctx.session(), next);
        ctx.session().setDocsResultsPage(0);
        ctx.replaceTopAndEnter(Phase.DOCS_RESULTS);
        return Transition.None.INSTANCE;
    }
}
