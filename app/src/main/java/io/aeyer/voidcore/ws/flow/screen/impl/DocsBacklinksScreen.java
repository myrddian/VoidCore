package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.auth.UserRepository;
import io.aeyer.voidcore.documents.DocumentRow;
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
import java.util.Optional;

/**
 * Backlinks list (PR-7, SPEC-documents §6). Shows docs that link
 * TO the current document via {@code ~slug} references in their
 * body. Pushed by {@code [B]} in {@link DocumentViewScreen};
 * {@code [Q]} returns to the doc viewer (standard nav-stack pop).
 *
 * <p>Visibility filtering applied at the SQL layer per
 * {@link DocumentView#canRead} — sources the user can't read are
 * silently omitted.
 *
 * <p>Capped at 20 rows from the repo; v1 BBS scale doesn't need
 * paging here.
 */
@ScreenComponent
public class DocsBacklinksScreen implements Screen {

    private final UserRepository users;

    public DocsBacklinksScreen(UserRepository users) { this.users = users; }

    @Override public Phase phase() { return Phase.DOCS_BACKLINKS; }
    @Override public String name() { return "docs-backlinks"; }

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
        Long id = ctx.session().currentDocumentId();
        if (id == null) {
            ctx.pop();
            return Transition.None.INSTANCE;
        }
        Optional<DocumentRow> maybe = ctx.services().documents().byId(id);
        if (maybe.isEmpty()) {
            // Doc gone — bounce.
            ctx.session().setCurrentDocumentId(null);
            ctx.pop();
            return Transition.None.INSTANCE;
        }
        DocumentRow doc = maybe.get();

        List<DocumentRow> sources = ctx.services().documents()
                .findBacklinks(doc.id(), ctx.session());

        ArrayList<Row> rows = new ArrayList<>();
        rows.add(headerRow(0, doc, sources.size()));
        rows.add(DocsCommon.blank(1));

        if (sources.isEmpty()) {
            rows.add(Frames.colored(2,
                    "  (no backlinks — no other documents reference this one)",
                    "dark_grey"));
        } else {
            int rowN = 2;
            int n = 1;
            int max = Math.min(sources.size(), 9);
            for (int i = 0; i < max; i++) {
                DocumentRow d = sources.get(i);
                String handle = users.findById(d.authorId())
                        .map(UserRepository.UserRow::handle).orElse("?");
                rows.add(DocsCommon.docListRow(rowN++, n++, d, handle));
            }
            if (sources.size() > 9) {
                rows.add(Frames.colored(rowN++,
                        "  (and " + (sources.size() - 9)
                                + " more — search lands with PR-6)",
                        "dark_grey"));
            }
        }
        int rowN = 2 + Math.min(sources.size(), 9)
                + (sources.size() > 9 ? 1 : 0);
        if (sources.isEmpty()) rowN = 3;
        rows.add(DocsCommon.blank(rowN++));
        rows.add(Frames.row(rowN++,
                Frames.span("  [", "grey"),
                Frames.span("Q", "bright_yellow", true),
                Frames.span("] back to document", "grey")));

        ctx.send(Frames.update("main", 76, rows));
        int max = Math.min(sources.size(), 9);
        ctx.send(new InputPrompt("keystroke", "backlinks:", null,
                DocsCommon.numberedKeys(max, "Q"), null));
        return Transition.None.INSTANCE;
    }

    private static Row headerRow(int rowN, DocumentRow doc, int total) {
        String left = "  == BACKLINKS: " + doc.title() + " ==";
        String right = total + " doc" + (total == 1 ? "" : "s");
        int targetCol = 60;
        int pad = Math.max(1, targetCol - left.length() - right.length());
        return Frames.row(rowN,
                Frames.span(left, "bright_yellow", true),
                Frames.span(" ".repeat(pad), null),
                Frames.span(right, "grey"));
    }

    @Override
    public Transition onKey(BbsContext ctx, String k) {
        if ("Q".equals(k)) {
            ctx.pop();
            return Transition.None.INSTANCE;
        }
        if (k.length() == 1 && k.charAt(0) >= '1' && k.charAt(0) <= '9') {
            handleOpen(ctx, k.charAt(0) - '0');
        }
        return Transition.None.INSTANCE;
    }

    private void handleOpen(BbsContext ctx, int n) {
        Long id = ctx.session().currentDocumentId();
        if (id == null) return;
        List<DocumentRow> sources = ctx.services().documents()
                .findBacklinks(id, ctx.session());
        if (n < 1 || n > Math.min(sources.size(), 9)) return;
        DocumentRow target = sources.get(n - 1);
        ctx.session().setCurrentDocumentId(target.id());
        ctx.push(Phase.DOCUMENT_SCREEN);
    }
}
