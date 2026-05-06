package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.documents.DocumentFilter;
import io.aeyer.voidcore.documents.DocumentKind;
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
import java.util.Map;

/**
 * Kind facet picker (PR-5). Lists the distinct kinds in the current
 * filter's set with counts; numbered selection extends the filter
 * with that kind and {@code replaceTopAndEnter(DOCS_RESULTS)} so the
 * picker is replaced (no nested back-stack of pickers).
 *
 * <p>Empty / single-value pickers self-pop on enter — there's nothing
 * meaningful to choose.
 */
@ScreenComponent
public class DocsFacetKindScreen implements Screen {

    @Override public Phase phase() { return Phase.DOCS_FACET_KIND; }
    @Override public String name() { return "docs-facet-kind"; }

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
        Map<DocumentKind, Long> counts = docs.kindFacetCounts(cur, ctx.session());
        if (counts.isEmpty()) {
            ctx.send(Frames.notify("notifications",
                    "no documents in scope", "warn", 2000));
            ctx.pop();
            return Transition.None.INSTANCE;
        }

        ArrayList<Row> rows = new ArrayList<>();
        rows.add(Frames.colored(0, "  == " + DocsCommon.facetPath("by-kind") + " ==            cd into kind",
                "bright_yellow"));
        rows.add(DocsCommon.blank(1));

        // Render in DocumentKind enum order; assign sequential numbers
        // so the user sees [1]..[N] regardless of which kinds exist.
        ArrayList<DocumentKind> visible = new ArrayList<>();
        for (DocumentKind k : DocumentKind.values()) {
            if (counts.containsKey(k)) visible.add(k);
        }
        int rowN = 2;
        int n = 1;
        for (DocumentKind k : visible) {
            rows.add(facetRow(rowN++, n++, k.wireValue() + "/", counts.get(k)));
        }
        rows.add(DocsCommon.blank(rowN++));
        rows.add(Frames.row(rowN++,
                Frames.span("  [", "grey"),
                Frames.span(".", "bright_yellow", true),
                Frames.span("] up  [", "grey"),
                Frames.span("Q", "bright_yellow", true),
                Frames.span("] back to menu", "grey")));

        ctx.send(Frames.update("main", 76, rows));
        // Cap at 9 numbered options for single-digit prompt.
        int maxN = Math.min(visible.size(), 9);
        ctx.send(new InputPrompt("keystroke", "docs:/by-kind/", null,
                DocsCommon.numberedKeys(maxN, ".Q"), null));

        // Stash the visible kinds list on the session so onKey can map
        // numbers back to kinds without re-running the count query.
        // Keep it simple: we reconstruct via the same enum-iteration
        // order in onKey by re-reading counts. (Cheap; cached query.)
        return Transition.None.INSTANCE;
    }

    private static Row facetRow(int rowN, int n, String label, long count) {
        String num = "[" + n + "]";
        return Frames.row(rowN,
                Frames.span("  ", null),
                Frames.span(num, "bright_yellow", true),
                Frames.span(" ", null),
                Frames.span(DocsCommon.padRight(label, 28), "default"),
                Frames.span(String.valueOf(count), "grey"));
    }

    @Override
    public Transition onKey(BbsContext ctx, String k) {
        if (".".equals(k)) {
            ctx.pop();
            return Transition.None.INSTANCE;
        }
        if ("Q".equals(k)) {
            // Pop all the way back to the menu — pop until empty would
            // logout, so use a single pop and rely on the caller's
            // back-stack to surface the menu naturally. (Hub is one
            // level below; pop pops to hub, hub's [Q] pops to menu.)
            ctx.pop();
            return Transition.None.INSTANCE;
        }
        if (k.length() != 1 || k.charAt(0) < '1' || k.charAt(0) > '9') {
            return Transition.None.INSTANCE;
        }
        int n = k.charAt(0) - '0';
        DocumentFilter cur = DocsCommon.currentFilter(ctx.session());
        Map<DocumentKind, Long> counts = ctx.services().documents()
                .kindFacetCounts(cur, ctx.session());
        ArrayList<DocumentKind> visible = new ArrayList<>();
        for (DocumentKind dk : DocumentKind.values()) {
            if (counts.containsKey(dk)) visible.add(dk);
        }
        if (n < 1 || n > visible.size()) return Transition.None.INSTANCE;

        DocumentKind picked = visible.get(n - 1);
        DocumentFilter next = cur.withKind(picked);
        DocsCommon.writeFilter(ctx.session(), next);
        ctx.session().setDocsResultsPage(0);
        ctx.replaceTopAndEnter(Phase.DOCS_RESULTS);
        return Transition.None.INSTANCE;
    }
}
