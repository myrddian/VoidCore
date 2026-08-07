package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.auth.UserRepository;
import io.aeyer.voidcore.documents.DocumentFilter;
import io.aeyer.voidcore.documents.DocumentRow;
import io.aeyer.voidcore.documents.DocumentSort;
import io.aeyer.voidcore.instance.InstanceFeature;
import io.aeyer.voidcore.ws.flow.Banner;
import io.aeyer.voidcore.ws.flow.Frames;
import io.aeyer.voidcore.ws.flow.layout.Element;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.flow.screen.ScreenApp;
import io.aeyer.voidcore.ws.flow.screen.ScreenComponent;
import io.aeyer.voidcore.ws.flow.screen.ScreenText;
import io.aeyer.voidcore.ws.flow.screen.Transition;
import io.aeyer.voidcore.ws.flow.ui.AppEvent;
import io.aeyer.voidcore.ws.flow.view.DocumentView;
import io.aeyer.voidcore.ws.protocol.ServerMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * Filtered document list. Breadcrumb header, the paginated results, and
 * the "cd further" options for facets the filter doesn't already
 * constrain.
 *
 * <h2>The nine-row cap is gone</h2>
 *
 * <p>This screen used to fetch {@code PAGE_SIZE} rows and render only
 * nine of them — not for space, but because the numbered open keys are
 * single digits. On the {@code list} element the highlight reaches every
 * row, so the whole page renders and digits stay a shortcut to the first
 * nine.
 *
 * <h2>Keys</h2>
 * <ul>
 *   <li>{@code 1..9} — open that row directly; arrows or {@code j}/{@code k}
 *       plus {@code Enter} reach any row.</li>
 *   <li>{@code J} / {@code K} — next / previous page. Upper case, and
 *       distinct from the list's lower-case {@code j}/{@code k} movement:
 *       the widget consumes only what it handles, so the two coexist.</li>
 *   <li>{@code T}/{@code B}/{@code W} — push the matching facet picker.</li>
 *   <li>{@code .} — drop the most-recently-added facet; pops to hub if the
 *       filter ends empty.</li>
 *   <li>{@code S} — cycle sort. {@code /} — filter expression.
 *       {@code Q} — back to menu.</li>
 * </ul>
 */
@ScreenComponent
public class DocsResultsScreen extends ScreenApp {

    /** Widget id, echoed back on {@code list.selected}. */
    private static final String LIST_ID = "docs";

    private final UserRepository users;

    public DocsResultsScreen(UserRepository users) { this.users = users; }

    @Override public Phase phase() { return Phase.DOCS_RESULTS; }
    @Override public String name() { return "docs-results"; }
    @Override protected String appKey(BbsContext ctx) { return "docs-results"; }

    @Override
    public List<String> topics(BbsContext ctx) {
        return ScreenFeatureGate.withTopic(List.of(DocumentView.TOPIC));
    }

    @Override
    public Transition onEnter(BbsContext ctx) {
        if (!ScreenFeatureGate.ensureEnabled(ctx, InstanceFeature.INFO_DOCS, "info / docs")) {
            return Transition.None.INSTANCE;
        }
        DocumentFilter filter = DocsCommon.currentFilter(ctx.session());
        if (filter.isEmpty()) {
            // Defensive: results should always have a filter (entered via a
            // picker). If empty, route back to the hub.
            ctx.replaceTopAndEnter(Phase.DOCS_HUB);
            return Transition.None.INSTANCE;
        }
        ctx.persistCurrentScreen(
                "{\"kind\":\"docs_results\",\"filter\":\"" + filter.serialise() + "\"}");
        Transition t = super.onEnter(ctx);
        // ScreenApp minimises the banner for editor-style screens; this one
        // is a browsing surface, so put the full banner back.
        ctx.send(Frames.update("banner", 2, Banner.rows()));
        return t;
    }

    @Override
    protected Element compose(BbsContext ctx) {
        DocumentFilter filter = DocsCommon.currentFilter(ctx.session());
        DocumentView docs = ctx.services().documents();

        long total = docs.countByFilter(filter, ctx.session());
        int totalPages = totalPages(total);
        int page = clampPage(ctx, totalPages);
        DocumentSort sort = DocumentSort.parse(ctx.session().docsResultsSort());
        List<DocumentRow> rows = pageRows(ctx, filter, sort, page);

        List<Element> children = new ArrayList<>();
        children.add(new Element.Header(
                DocsCommon.displayPath(filter.breadcrumb()),
                total + " doc" + (total == 1 ? "" : "s")
                        + " · page " + (page + 1) + "/" + Math.max(1, totalPages)));
        children.add(new Element.Text(
                "  sort: " + sort.wireValue() + "    ([S] cycles)", "grey"));
        children.add(new Element.Spacer(1));

        if (rows.isEmpty()) {
            children.add(new Element.Text("  (no documents)", "dark_grey"));
        } else {
            children.add(new Element.ListView(LIST_ID, items(rows), selectedId(ctx, rows)));
        }

        children.add(new Element.Spacer(1));
        for (Narrow n : narrowOptions(ctx, filter)) {
            children.add(new Element.Text("  [" + n.key() + "] cd " + n.label(), "grey"));
        }

        children.add(new Element.Spacer(1));
        children.add(new Element.KeyMenu(keyMenu(page, totalPages)));
        return new Element.VStack(children, 0);
    }

    private List<Element.ListView.Item> items(List<DocumentRow> rows) {
        List<Element.ListView.Item> items = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            DocumentRow d = rows.get(i);
            String title = d.title() == null || d.title().isBlank() ? "(untitled)" : d.title();
            String author = users.findById(d.authorId())
                    .map(UserRepository.UserRow::handle).orElse("?");
            String prefix = i < 9 ? "[" + (i + 1) + "] " : "    ";
            items.add(new Element.ListView.Item(
                    String.valueOf(d.id()),
                    prefix + ScreenText.truncate(title, 48) + "  " + author,
                    DocsCommon.formatWhen(d.updatedAt())));
        }
        return items;
    }

    /** Re-highlight the document just read, when the user comes back. */
    private String selectedId(BbsContext ctx, List<DocumentRow> rows) {
        Long current = ctx.session().currentDocumentId();
        if (current == null) return null;
        return rows.stream().anyMatch(d -> d.id() == current) ? String.valueOf(current) : null;
    }

    private record Narrow(String key, String label) {}

    /** Facets worth offering: multi-valued, and not already constrained. */
    private List<Narrow> narrowOptions(BbsContext ctx, DocumentFilter filter) {
        DocumentView docs = ctx.services().documents();
        List<Narrow> out = new ArrayList<>();
        if (filter.tagsList().isEmpty() || furtherTagsAvailable(docs, filter, ctx)) {
            out.add(new Narrow("T", "by-tag/"));
        }
        if (filter.authorId().isEmpty()
                && docs.authorFacetCounts(filter, ctx.session(), 2).size() > 1) {
            out.add(new Narrow("B", "by-author/"));
        }
        if (filter.year().isEmpty()
                && docs.whenFacetCounts(filter, ctx.session()).size() > 1) {
            out.add(new Narrow("W", "by-year/"));
        }
        return out;
    }

    private List<Element.KeyMenu.KeyEntry> keyMenu(int page, int totalPages) {
        List<Element.KeyMenu.KeyEntry> keys = new ArrayList<>();
        keys.add(new Element.KeyMenu.KeyEntry("↑↓", "move"));
        keys.add(new Element.KeyMenu.KeyEntry("Enter", "open"));
        keys.add(new Element.KeyMenu.KeyEntry(".", "up"));
        if (totalPages > 1 && page > 0) keys.add(new Element.KeyMenu.KeyEntry("K", "prev page"));
        if (totalPages > 1 && page < totalPages - 1) keys.add(new Element.KeyMenu.KeyEntry("J", "next page"));
        keys.add(new Element.KeyMenu.KeyEntry("/", "filter expr"));
        keys.add(new Element.KeyMenu.KeyEntry("S", "sort"));
        keys.add(new Element.KeyMenu.KeyEntry("Q", "back to menu"));
        return keys;
    }

    @Override
    protected void onEvent(BbsContext ctx, AppEvent ev) {
        switch (ev) {
            case AppEvent.ListSelected ls -> {
                if (LIST_ID.equals(ls.widgetId())) open(ctx, ls.itemId());
            }
            case AppEvent.FieldCancel fc -> backOneFacet(ctx);
            default -> { /* no other widgets on this screen */ }
        }
    }

    @Override
    public Transition onKey(BbsContext ctx, String k) {
        switch (k) {
            case "." -> backOneFacet(ctx);
            case "Q" -> {
                ctx.session().setDocsFilter(null);
                ctx.session().setDocsResultsPage(null);
                popAndExit(ctx);
            }
            case "J" -> advancePage(ctx, +1);
            case "K" -> advancePage(ctx, -1);
            case "T" -> pushAndExit(ctx, Phase.DOCS_FACET_TAG);
            case "B" -> pushAndExit(ctx, Phase.DOCS_FACET_BY);
            case "W" -> pushAndExit(ctx, Phase.DOCS_FACET_WHEN);
            case "/" -> pushAndExit(ctx, Phase.DOCS_SEARCH_PROMPT);
            case "S" -> cycleSort(ctx);
            default -> {
                if (k.length() == 1 && k.charAt(0) >= '1' && k.charAt(0) <= '9') {
                    openByOrdinal(ctx, k.charAt(0) - '0');
                }
            }
        }
        return Transition.None.INSTANCE;
    }

    private void cycleSort(BbsContext ctx) {
        DocumentSort next = DocumentSort.parse(ctx.session().docsResultsSort()).cycle();
        ctx.session().setDocsResultsSort(next.wireValue());
        // Page N under the old order means nothing under the new one.
        ctx.session().setDocsResultsPage(0);
        repaintNow(ctx);
        ctx.send(defaultInputPrompt(ctx));
    }

    private void advancePage(BbsContext ctx, int delta) {
        Integer cur = ctx.session().docsResultsPage();
        int page = cur == null ? 0 : cur;
        ctx.session().setDocsResultsPage(Math.max(0, page + delta));
        repaintNow(ctx);
        ctx.send(defaultInputPrompt(ctx));
    }

    /** Selection arrives as an item id — a document id as a string. */
    private void open(BbsContext ctx, String itemId) {
        long id;
        try {
            id = Long.parseLong(itemId);
        } catch (NumberFormatException e) {
            return;
        }
        // Re-check against the page actually rendered: the id came off the
        // wire and must be one this filter and this user can see.
        if (currentPageRows(ctx).stream().noneMatch(d -> d.id() == id)) return;
        enter(ctx, id);
    }

    private void openByOrdinal(BbsContext ctx, int n) {
        List<DocumentRow> rows = currentPageRows(ctx);
        if (n < 1 || n > rows.size()) return;
        enter(ctx, rows.get(n - 1).id());
    }

    private void enter(BbsContext ctx, long documentId) {
        ctx.session().setCurrentDocumentId(documentId);
        pushAndExit(ctx, Phase.DOCUMENT_SCREEN);
    }

    /**
     * {@code [.]} drops the most-recently-added facet. Without add-order
     * tracking, "most recent" maps to a deterministic priority:
     * tag (last in the list) → year → author → kind. Pops to hub if the
     * filter ends empty.
     */
    private void backOneFacet(BbsContext ctx) {
        DocumentFilter cur = DocsCommon.currentFilter(ctx.session());
        DocumentFilter next;
        if (!cur.tagsList().isEmpty()) {
            next = cur.dropTag(cur.tagsList().get(cur.tagsList().size() - 1));
        } else if (cur.year().isPresent() || cur.month().isPresent()) {
            next = cur.dropWhen();
        } else if (cur.authorId().isPresent()) {
            next = cur.dropAuthor();
        } else if (cur.kind().isPresent()) {
            next = cur.dropKind();
        } else {
            popAndExit(ctx);
            return;
        }
        if (next.isEmpty()) {
            ctx.session().setDocsFilter(null);
            ctx.session().setDocsResultsPage(null);
            replaceTopAndExit(ctx, Phase.DOCS_HUB);
            return;
        }
        DocsCommon.writeFilter(ctx.session(), next);
        ctx.session().setDocsResultsPage(0);
        repaintNow(ctx);
        ctx.send(defaultInputPrompt(ctx));
    }

    /**
     * Keystroke set: digits for the first nine rows, facet letters that are
     * actually offered, paging where a neighbour page exists, plus the
     * always-available controls.
     */
    @Override
    protected ServerMessage.InputPrompt defaultInputPrompt(BbsContext ctx) {
        DocumentFilter filter = DocsCommon.currentFilter(ctx.session());
        int totalPages = totalPages(ctx.services().documents().countByFilter(filter, ctx.session()));
        int page = clampPage(ctx, totalPages);

        StringBuilder keys = new StringBuilder();
        int digits = Math.min(9, currentPageRows(ctx).size());
        for (int i = 1; i <= digits; i++) keys.append(i);
        for (Narrow n : narrowOptions(ctx, filter)) keys.append(n.key());
        if (totalPages > 1 && page > 0) keys.append('K');
        if (totalPages > 1 && page < totalPages - 1) keys.append('J');
        keys.append(".QS/");
        return new ServerMessage.InputPrompt("keystroke", "docs:/", null, keys.toString(), null);
    }

    private int totalPages(long total) {
        return total == 0 ? 0 : (int) ((total + DocsCommon.PAGE_SIZE - 1) / DocsCommon.PAGE_SIZE);
    }

    private int clampPage(BbsContext ctx, int totalPages) {
        Integer box = ctx.session().docsResultsPage();
        int page = box == null ? 0 : Math.max(0, box);
        if (totalPages > 0 && page >= totalPages) {
            page = totalPages - 1;
            ctx.session().setDocsResultsPage(page);
        }
        return page;
    }

    /** The rows currently on screen — one query shape, used everywhere. */
    private List<DocumentRow> currentPageRows(BbsContext ctx) {
        DocumentFilter filter = DocsCommon.currentFilter(ctx.session());
        DocumentSort sort = DocumentSort.parse(ctx.session().docsResultsSort());
        int totalPages = totalPages(ctx.services().documents().countByFilter(filter, ctx.session()));
        return pageRows(ctx, filter, sort, clampPage(ctx, totalPages));
    }

    private List<DocumentRow> pageRows(BbsContext ctx, DocumentFilter filter,
                                       DocumentSort sort, int page) {
        // Whole page, not nine: the cap existed only because the open keys
        // were single digits, and the list reaches every row.
        return ctx.services().documents().findByFilter(
                filter, ctx.session(), sort,
                page * DocsCommon.PAGE_SIZE, DocsCommon.PAGE_SIZE);
    }

    private static boolean furtherTagsAvailable(DocumentView docs,
                                                DocumentFilter filter,
                                                BbsContext ctx) {
        return docs.tagFacetCounts(filter, ctx.session(), 2).stream()
                .anyMatch(t -> !filter.tagsList().contains(t.tag()));
    }
}
