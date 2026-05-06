package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.auth.UserRepository;
import io.aeyer.voidcore.documents.DocumentFilter;
import io.aeyer.voidcore.documents.DocumentKind;
import io.aeyer.voidcore.documents.DocumentRepository;
import io.aeyer.voidcore.documents.DocumentRow;
import io.aeyer.voidcore.documents.Status;
import io.aeyer.voidcore.documents.Visibility;
import io.aeyer.voidcore.instance.InstanceFeature;
import io.aeyer.voidcore.ws.flow.Banner;
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
 * Documents-info default landing (PR-5, SPEC-documents §4.2). Shows
 * total visible doc count, the {@value DocsCommon#HUB_RECENT_COUNT}
 * most-recently-updated docs (numbered for direct open), and the
 * virtual-directory entry points as letter keys.
 *
 * <p>Entered via {@code [I]} from the main menu. Resets the session's
 * {@code docsFilter} on enter so each fresh visit starts empty —
 * carry-over from a previous session is intentional only when the
 * user navigates back via {@code [..]} from a results screen, in
 * which case they don't go through here.
 *
 * <p>Subscribes to {@link DocumentView#TOPIC} so an edit/delete on
 * another session repaints (recent list reflects the change).
 */
@ScreenComponent
public class DocsHubScreen implements Screen {

    private final UserRepository users;
    private final DocumentRepository docs;

    public DocsHubScreen(UserRepository users, DocumentRepository docs) {
        this.users = users;
        this.docs = docs;
    }

    @Override public Phase phase() { return Phase.DOCS_HUB; }
    @Override public String name() { return "docs-hub"; }

    @Override
    public List<String> topics(BbsContext ctx) {
        return ScreenFeatureGate.withTopic(List.of(DocumentView.TOPIC));
    }

    @Override
    public Transition onEnter(BbsContext ctx) {
        if (!ScreenFeatureGate.ensureEnabled(ctx, InstanceFeature.INFO_DOCS, "info / docs")) {
            return Transition.None.INSTANCE;
        }
        // Fresh entry to the hub clears any leftover filter state.
        // Pickers and DOCS_RESULTS write their own; coming in from
        // anywhere else means starting over.
        ctx.session().setDocsFilter(null);
        ctx.session().setDocsResultsPage(null);
        ctx.persistCurrentScreen("{\"kind\":\"docs_hub\"}");
        // Restore the full banner in case we're returning from a ScreenApp
        // (e.g. DocumentScreen) that minimised it.
        ctx.send(Frames.update("banner", 2, Banner.rows()));

        DocumentView docs = ctx.services().documents();
        DocumentFilter empty = DocumentFilter.empty();
        long total = docs.countByFilter(empty, ctx.session());
        List<DocumentRow> recent = docs.findByFilter(
                empty, ctx.session(), 0, DocsCommon.HUB_RECENT_COUNT);

        // Facet summary counts for the hub display.
        long kindCount = docs.kindFacetCounts(empty, ctx.session()).size();
        long tagCount = docs.tagFacetCounts(empty, ctx.session(), 1000).size();
        long authorCount = docs.authorFacetCounts(empty, ctx.session(), 1000).size();
        var years = docs.whenFacetCounts(empty, ctx.session());

        ArrayList<Row> rows = new ArrayList<>();
        rows.add(DocsCommon.totalRow(0, total, null));
        rows.add(DocsCommon.blank(1));

        if (recent.isEmpty()) {
            rows.add(Frames.colored(2, "  (no documents)", "dark_grey"));
        } else {
            rows.add(Frames.colored(2, "  ls recent/", "grey"));
            int rowN = 3;
            int n = 1;
            for (DocumentRow d : recent) {
                String handle = users.findById(d.authorId())
                        .map(UserRepository.UserRow::handle).orElse("?");
                rows.add(DocsCommon.docListRow(rowN++, n++, d, handle));
            }
        }
        int rowN = 3 + Math.min(recent.size(), DocsCommon.HUB_RECENT_COUNT);
        rows.add(DocsCommon.blank(rowN++));
        rows.add(Frames.colored(rowN++, "  cd into:", "grey"));
        rows.add(facetMenuRow(rowN++, "K", "by-kind/    ",
                kindCount + " branch" + (kindCount == 1 ? "" : "es")));
        rows.add(facetMenuRow(rowN++, "T", "by-tag/     ",
                tagCount + " tag dir" + (tagCount == 1 ? "" : "s")));
        rows.add(facetMenuRow(rowN++, "B", "by-author/  ",
                authorCount + " author dir" + (authorCount == 1 ? "" : "s")));
        String yearText = years.isEmpty()
                ? "(none)"
                : years.stream()
                        .map(y -> String.valueOf(y.year()))
                        .reduce((a, b) -> a + " / " + b)
                        .orElse("");
        rows.add(facetMenuRow(rowN++, "W", "by-year/    ", yearText));
        rows.add(DocsCommon.blank(rowN++));
        rows.add(Frames.row(rowN++,
                Frames.span("  [", "grey"),
                Frames.span("N", "bright_yellow", true),
                Frames.span("] new document  ", "grey"),
                Frames.span("[", "grey"),
                Frames.span("/", "bright_yellow", true),
                Frames.span("] filter expr  ", "grey"),
                Frames.span("[", "grey"),
                Frames.span("Q", "bright_yellow", true),
                Frames.span("] back to menu", "grey")));

        ctx.send(Frames.update("main", 76, rows));
        ctx.send(new InputPrompt("keystroke", "docs:/", null,
                DocsCommon.numberedKeys(recent.size(), "KTBWNQ/"), null));
        return Transition.None.INSTANCE;
    }

    private static Row facetMenuRow(int rowN, String letter, String label, String summary) {
        return Frames.row(rowN,
                Frames.span("  [", "grey"),
                Frames.span(letter, "bright_yellow", true),
                Frames.span("] ", "grey"),
                Frames.span(label, "grey"),
                Frames.span(summary, "default"));
    }

    @Override
    public Transition onKey(BbsContext ctx, String k) {
        // Numbered keys 1..9 open the corresponding recent doc.
        if (k.length() == 1 && k.charAt(0) >= '1' && k.charAt(0) <= '9') {
            handleOpenRecent(ctx, k.charAt(0) - '0');
            return Transition.None.INSTANCE;
        }
        switch (k) {
            case "Q" -> ctx.pop();
            case "K" -> ctx.push(Phase.DOCS_FACET_KIND);
            case "T" -> ctx.push(Phase.DOCS_FACET_TAG);
            case "B" -> ctx.push(Phase.DOCS_FACET_BY);
            case "W" -> ctx.push(Phase.DOCS_FACET_WHEN);
            case "N" -> handleNewDoc(ctx);
            case "/" -> ctx.push(Phase.DOCS_SEARCH_PROMPT);
            default -> { /* ignored — keystroke prompt restricts already */ }
        }
        return Transition.None.INSTANCE;
    }

    private void handleOpenRecent(BbsContext ctx, int n) {
        DocumentView docs = ctx.services().documents();
        List<DocumentRow> recent = docs.findByFilter(
                DocumentFilter.empty(), ctx.session(), 0,
                DocsCommon.HUB_RECENT_COUNT);
        if (n < 1 || n > recent.size()) return;
        DocumentRow d = recent.get(n - 1);
        ctx.session().setCurrentDocumentId(d.id());
        ctx.push(Phase.DOCUMENT_SCREEN);
    }

    private void handleNewDoc(BbsContext ctx) {
        Long uid = ctx.session().userId();
        if (uid == null) {
            ctx.send(Frames.notify("notifications",
                    "must be signed in to create a document",
                    "warn", 2500));
            return;
        }
        // Auto-generated slug — uses millisecond timestamp suffix to avoid
        // collisions in the rare race where two users create at the same
        // millisecond. Users can rename later via the title-edit flow
        // (slug isn't editable in v1; it's a permalink).
        String slug = "untitled-" + System.currentTimeMillis();
        long id;
        try {
            id = docs.insert(
                    slug,
                    "Untitled",
                    DocumentKind.ARTICLE,
                    "",                                  // empty body
                    ctx.services().json().createObjectNode(),  // empty frontmatter
                    java.util.List.of(),                 // no tags
                    uid,
                    Visibility.PUBLIC,
                    Status.DRAFT);
        } catch (RuntimeException e) {
            ctx.send(Frames.notify("notifications",
                    "could not create document — try again",
                    "alert", 3000));
            return;
        }
        ctx.publish(DocumentView.TOPIC);   // refresh other peers' lists
        ctx.session().setCurrentDocumentId(id);
        ctx.push(Phase.DOCUMENT_SCREEN);
    }
}
