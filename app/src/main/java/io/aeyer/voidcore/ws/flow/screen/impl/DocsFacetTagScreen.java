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
 * Tag facet picker (PR-5). Top-{@value DocsCommon#PICKER_TOP_N} tags
 * by count within the current filter, ordered count-desc then alpha;
 * tags already on the filter are filtered out at render time
 * (re-narrowing by an existing tag is a no-op).
 *
 * <p>Numbered selection extends the filter and re-enters
 * {@code DOCS_RESULTS}.
 */
@ScreenComponent
public class DocsFacetTagScreen implements Screen {

    @Override public Phase phase() { return Phase.DOCS_FACET_TAG; }
    @Override public String name() { return "docs-facet-tag"; }

    @Override
    public List<String> topics(BbsContext ctx) {
        return ScreenFeatureGate.withTopic(List.of(DocumentView.TOPIC));
    }

    @Override
    public Transition onEnter(BbsContext ctx) {
        if (!ScreenFeatureGate.ensureEnabled(ctx, InstanceFeature.INFO_DOCS, "info / docs")) {
            return Transition.None.INSTANCE;
        }
        DocumentView docs = ctx.services().documents();
        DocumentFilter cur = DocsCommon.currentFilter(ctx.session());
        List<FacetCount.Tag> all = docs.tagFacetCounts(
                cur, ctx.session(), DocsCommon.PICKER_TOP_N);
        // Drop tags already in the filter.
        List<FacetCount.Tag> visible = all.stream()
                .filter(t -> !cur.tagsList().contains(t.tag()))
                .toList();
        if (visible.isEmpty()) {
            ctx.send(Frames.notify("notifications",
                    "no further tag refinement available", "info", 2000));
            ctx.pop();
            return Transition.None.INSTANCE;
        }

        ArrayList<Row> rows = new ArrayList<>();
        rows.add(Frames.colored(0,
                "  == " + DocsCommon.facetPath("by-tag") + " ==             cd into tag",
                "bright_yellow"));
        rows.add(DocsCommon.blank(1));
        int rowN = 2;
        int n = 1;
        // Cap at 9 single-digit picks; if there are more, the user
        // can refine via search (PR-6) once that lands.
        int max = Math.min(visible.size(), 9);
        for (int i = 0; i < max; i++) {
            FacetCount.Tag t = visible.get(i);
            rows.add(numberedRow(rowN++, n++, t.tag() + "/", t.count()));
        }
        if (visible.size() > 9) {
            rows.add(Frames.colored(rowN++,
                "  (and " + (visible.size() - 9)
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
        ctx.send(new InputPrompt("keystroke", "docs:/by-tag/", null,
                DocsCommon.numberedKeys(max, ".Q"), null));
        return Transition.None.INSTANCE;
    }

    private static Row numberedRow(int rowN, int n, String label, long count) {
        return Frames.row(rowN,
                Frames.span("  ", null),
                Frames.span("[" + n + "]", "bright_yellow", true),
                Frames.span(" ", null),
                Frames.span(DocsCommon.padRight(label, 28), "default"),
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
        List<FacetCount.Tag> all = ctx.services().documents().tagFacetCounts(
                cur, ctx.session(), DocsCommon.PICKER_TOP_N);
        List<FacetCount.Tag> visible = all.stream()
                .filter(t -> !cur.tagsList().contains(t.tag()))
                .toList();
        if (n < 1 || n > Math.min(visible.size(), 9)) return Transition.None.INSTANCE;

        FacetCount.Tag picked = visible.get(n - 1);
        DocumentFilter next = cur.withTag(picked.tag());
        DocsCommon.writeFilter(ctx.session(), next);
        ctx.session().setDocsResultsPage(0);
        ctx.replaceTopAndEnter(Phase.DOCS_RESULTS);
        return Transition.None.INSTANCE;
    }
}
