package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.auth.UserRepository;
import io.aeyer.voidcore.documents.DocumentFilter;
import io.aeyer.voidcore.documents.DocumentRow;
import io.aeyer.voidcore.documents.DocumentSort;
import io.aeyer.voidcore.instance.InstanceFeature;
import io.aeyer.voidcore.ws.flow.Banner;
import io.aeyer.voidcore.ws.flow.Frames;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.flow.screen.Screen;
import io.aeyer.voidcore.ws.flow.screen.ScreenComponent;
import io.aeyer.voidcore.ws.flow.screen.Transition;
import io.aeyer.voidcore.ws.flow.view.DocumentView;
import io.aeyer.voidcore.ws.protocol.ServerMessage;
import io.aeyer.voidcore.ws.protocol.ServerMessage.InputPrompt;
import io.aeyer.voidcore.ws.protocol.ServerMessage.Row;

import java.util.ArrayList;
import java.util.List;

/**
 * Filtered list view (PR-5). Renders the breadcrumb header, the
 * paginated document list, and the "cd further" picker row
 * (only multi-value facets that aren't already constrained).
 *
 * <h2>Keys</h2>
 * <ul>
 *   <li>{@code 1..9} — open the corresponding row's doc.</li>
 *   <li>{@code J} — next page (no-op past the end).</li>
 *   <li>{@code K} — prev page (no-op before page 1).</li>
 *   <li>{@code T}/{@code B}/{@code W} — push the matching picker
 *       (kind picker is the only one not surfaced here, since we're
 *       always entered via either the kind picker or another picker
 *       — kind narrowing happens at hub level).</li>
 *   <li>{@code .} — drop the most-recently-added facet from the
 *       filter and re-enter; pops to hub if the filter ends empty.</li>
 *   <li>{@code Q} — back to menu (pops the whole stack down).</li>
 * </ul>
 */
@ScreenComponent
public class DocsResultsScreen implements Screen {

    private final UserRepository users;

    public DocsResultsScreen(UserRepository users) { this.users = users; }

    @Override public Phase phase() { return Phase.DOCS_RESULTS; }
    @Override public String name() { return "docs-results"; }

    @Override
    public List<String> topics(BbsContext ctx) {
        return ScreenFeatureGate.withTopic(List.of(DocumentView.TOPIC));
    }

    @Override
    public Transition onEnter(BbsContext ctx) {
        if (!ScreenFeatureGate.ensureEnabled(ctx, InstanceFeature.INFO_DOCS, "info / docs")) {
            return Transition.None.INSTANCE;
        }
        // Restore the full banner in case we're returning from a ScreenApp
        // (e.g. DocumentScreen) that minimised it.
        ctx.send(Frames.update("banner", 2, Banner.rows()));
        DocumentFilter filter = DocsCommon.currentFilter(ctx.session());
        if (filter.isEmpty()) {
            // Defensive: results screen should always have a filter
            // (entered via picker). If empty, route back to hub.
            ctx.replaceTopAndEnter(Phase.DOCS_HUB);
            return Transition.None.INSTANCE;
        }
        DocumentView docs = ctx.services().documents();
        long total = docs.countByFilter(filter, ctx.session());
        int totalPages = total == 0 ? 0
                : (int) ((total + DocsCommon.PAGE_SIZE - 1) / DocsCommon.PAGE_SIZE);
        Integer pageBox = ctx.session().docsResultsPage();
        int page = pageBox == null ? 0 : Math.max(0, pageBox);
        if (totalPages > 0 && page >= totalPages) {
            page = totalPages - 1;
            ctx.session().setDocsResultsPage(page);
        }
        int offset = page * DocsCommon.PAGE_SIZE;
        // Page size on screen capped at 9 because we're using single-
        // digit numbered open keys. The repo can return more but the
        // rendered chunk is 9.
        int pageRows = Math.min(DocsCommon.PAGE_SIZE, 9);
        DocumentSort sort = DocumentSort.parse(ctx.session().docsResultsSort());
        List<DocumentRow> rowsList = docs.findByFilter(
                filter, ctx.session(), sort, offset, pageRows);

        ctx.persistCurrentScreen(
                "{\"kind\":\"docs_results\",\"filter\":\""
                        + filter.serialise() + "\"}");

        ArrayList<Row> rendered = new ArrayList<>();
        rendered.add(DocsCommon.pageHeaderRow(0, total, page, totalPages, filter.breadcrumb()));
        rendered.add(Frames.row(1,
                Frames.span("  sort: ", "grey"),
                Frames.span(sort.wireValue(), "default"),
                Frames.span("    (", "grey"),
                Frames.span("S", "bright_yellow", true),
                Frames.span(" cycles)", "grey")));
        int rowN = 2;
        int n = 1;
        if (rowsList.isEmpty()) {
            rendered.add(Frames.colored(rowN++, "  (no documents)", "dark_grey"));
        } else {
            for (DocumentRow d : rowsList) {
                String handle = users.findById(d.authorId())
                        .map(UserRepository.UserRow::handle).orElse("?");
                rendered.add(DocsCommon.docListRow(rowN++, n++, d, handle));
            }
        }
        rendered.add(DocsCommon.blank(rowN++));

        // cd further — list only multi-value facets that the
        // current filter doesn't already constrain.
        ArrayList<Character> furtherKeys = new ArrayList<>();
        if (filter.kind().isEmpty()
                && docs.kindFacetCounts(filter, ctx.session()).size() > 1) {
            // Note: the kind picker is reachable from the hub; offering
            // it from results too is convenient. Letter K conflicts with
            // prev-page; use lowercase semantic but the keystroke filter
            // is uppercase. Use a different letter — pick "F"orm? No.
            // Skip kind narrowing from results for v1; user pops back
            // to hub for a fresh start. This keeps J/K free for paging.
        }
        if (filter.tagsList().isEmpty()
                || furtherTagsAvailable(docs, filter, ctx)) {
            rendered.add(narrowRow(rowN++, "T", "by-tag/"));
            furtherKeys.add('T');
        }
        if (filter.authorId().isEmpty()
                && docs.authorFacetCounts(filter, ctx.session(), 2).size() > 1) {
            rendered.add(narrowRow(rowN++, "B", "by-author/"));
            furtherKeys.add('B');
        }
        if (filter.year().isEmpty()
                && docs.whenFacetCounts(filter, ctx.session()).size() > 1) {
            rendered.add(narrowRow(rowN++, "W", "by-year/"));
            furtherKeys.add('W');
        }

        rendered.add(DocsCommon.blank(rowN++));
        ArrayList<ServerMessage.Span> footer = new ArrayList<>();
        footer.add(Frames.span("  [", "grey"));
        footer.add(Frames.span(".", "bright_yellow", true));
        footer.add(Frames.span("] up  ", "grey"));
        if (totalPages > 1) {
            if (page > 0) {
                footer.add(Frames.span("[", "grey"));
                footer.add(Frames.span("K", "bright_yellow", true));
                footer.add(Frames.span("] prev  ", "grey"));
            }
            if (page < totalPages - 1) {
                footer.add(Frames.span("[", "grey"));
                footer.add(Frames.span("J", "bright_yellow", true));
                footer.add(Frames.span("] next  ", "grey"));
            }
        }
        footer.add(Frames.span("[", "grey"));
        footer.add(Frames.span("/", "bright_yellow", true));
        footer.add(Frames.span("] filter expr  ", "grey"));
        footer.add(Frames.span("[", "grey"));
        footer.add(Frames.span("S", "bright_yellow", true));
        footer.add(Frames.span("] sort  ", "grey"));
        footer.add(Frames.span("[", "grey"));
        footer.add(Frames.span("Q", "bright_yellow", true));
        footer.add(Frames.span("] back to menu", "grey"));
        rendered.add(Frames.row(rowN++, footer.toArray(new ServerMessage.Span[0])));

        ctx.send(Frames.update("main", 76, rendered));

        // Keystroke set: 1..N for open, T/B/W for narrow, J/K for paging,
        // dot for back, Q for menu, / for search prompt, S for sort cycle.
        StringBuilder keys = new StringBuilder();
        for (int i = 1; i <= rowsList.size() && i <= 9; i++) keys.append(i);
        for (char c : furtherKeys) keys.append(c);
        if (totalPages > 1) {
            if (page > 0) keys.append('K');
            if (page < totalPages - 1) keys.append('J');
        }
        keys.append(".QS/");
        ctx.send(new InputPrompt("keystroke", "docs:/", null,
                keys.toString(), null));
        return Transition.None.INSTANCE;
    }

    private static boolean furtherTagsAvailable(DocumentView docs,
                                                DocumentFilter filter,
                                                BbsContext ctx) {
        return docs.tagFacetCounts(filter, ctx.session(), 2).stream()
                .anyMatch(t -> !filter.tagsList().contains(t.tag()));
    }

    private static Row narrowRow(int rowN, String letter, String label) {
        return Frames.row(rowN,
                Frames.span("  [", "grey"),
                Frames.span(letter, "bright_yellow", true),
                Frames.span("] cd ", "grey"),
                Frames.span(label, "default"));
    }

    @Override
    public Transition onKey(BbsContext ctx, String k) {
        if (".".equals(k)) {
            handleBackOneFacet(ctx);
            return Transition.None.INSTANCE;
        }
        if ("Q".equals(k)) {
            // Pop the whole docs stack — hub is at the bottom; pop
            // here pops to picker (already replaced) or hub.
            ctx.session().setDocsFilter(null);
            ctx.session().setDocsResultsPage(null);
            ctx.pop();
            return Transition.None.INSTANCE;
        }
        if ("J".equals(k)) {
            advancePage(ctx, +1);
            return Transition.None.INSTANCE;
        }
        if ("K".equals(k)) {
            advancePage(ctx, -1);
            return Transition.None.INSTANCE;
        }
        if ("T".equals(k)) {
            ctx.push(Phase.DOCS_FACET_TAG);
            return Transition.None.INSTANCE;
        }
        if ("B".equals(k)) {
            ctx.push(Phase.DOCS_FACET_BY);
            return Transition.None.INSTANCE;
        }
        if ("W".equals(k)) {
            ctx.push(Phase.DOCS_FACET_WHEN);
            return Transition.None.INSTANCE;
        }
        if ("/".equals(k)) {
            ctx.push(Phase.DOCS_SEARCH_PROMPT);
            return Transition.None.INSTANCE;
        }
        if ("S".equals(k)) {
            cycleSort(ctx);
            return Transition.None.INSTANCE;
        }
        if (k.length() == 1 && k.charAt(0) >= '1' && k.charAt(0) <= '9') {
            handleOpen(ctx, k.charAt(0) - '0');
        }
        return Transition.None.INSTANCE;
    }

    private void cycleSort(BbsContext ctx) {
        DocumentSort current = DocumentSort.parse(ctx.session().docsResultsSort());
        DocumentSort next = current.cycle();
        ctx.session().setDocsResultsSort(next.wireValue());
        // Reset to page 0 when changing sort — order is now different,
        // page-N "where you were" is meaningless under the new order.
        ctx.session().setDocsResultsPage(0);
        onEnter(ctx);
    }

    private void advancePage(BbsContext ctx, int delta) {
        Integer cur = ctx.session().docsResultsPage();
        int page = cur == null ? 0 : cur;
        ctx.session().setDocsResultsPage(Math.max(0, page + delta));
        onEnter(ctx);
    }

    private void handleOpen(BbsContext ctx, int n) {
        DocumentFilter filter = DocsCommon.currentFilter(ctx.session());
        Integer pageBox = ctx.session().docsResultsPage();
        int page = pageBox == null ? 0 : pageBox;
        int pageRows = Math.min(DocsCommon.PAGE_SIZE, 9);
        List<DocumentRow> rowsList = ctx.services().documents().findByFilter(
                filter, ctx.session(), page * DocsCommon.PAGE_SIZE, pageRows);
        if (n < 1 || n > rowsList.size()) return;
        ctx.session().setCurrentDocumentId(rowsList.get(n - 1).id());
        ctx.push(Phase.DOCUMENT_SCREEN);
    }

    /**
     * {@code [..]} drops the most-recently-added facet. Without
     * tracking add-order, "most recent" maps to a deterministic
     * priority: tag (last tag in the list) → year → author → kind.
     * Pops to hub if the filter ends empty.
     */
    private void handleBackOneFacet(BbsContext ctx) {
        DocumentFilter cur = DocsCommon.currentFilter(ctx.session());
        DocumentFilter next;
        if (!cur.tagsList().isEmpty()) {
            String last = cur.tagsList().get(cur.tagsList().size() - 1);
            next = cur.dropTag(last);
        } else if (cur.year().isPresent() || cur.month().isPresent()) {
            next = cur.dropWhen();
        } else if (cur.authorId().isPresent()) {
            next = cur.dropAuthor();
        } else if (cur.kind().isPresent()) {
            next = cur.dropKind();
        } else {
            ctx.pop();
            return;
        }
        if (next.isEmpty()) {
            ctx.session().setDocsFilter(null);
            ctx.session().setDocsResultsPage(null);
            ctx.replaceTopAndEnter(Phase.DOCS_HUB);
            return;
        }
        DocsCommon.writeFilter(ctx.session(), next);
        ctx.session().setDocsResultsPage(0);
        onEnter(ctx);
    }
}
