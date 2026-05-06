package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.documents.DocumentRepository;
import io.aeyer.voidcore.documents.DocumentRow;
import io.aeyer.voidcore.documents.ReleaseFrontmatter;
import io.aeyer.voidcore.instance.InstanceFeature;
import io.aeyer.voidcore.ws.flow.Frames;
import io.aeyer.voidcore.ws.flow.layout.Element;
import io.aeyer.voidcore.ws.flow.layout.Layout;
import io.aeyer.voidcore.ws.flow.layout.LayoutRenderer;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.flow.screen.Screen;
import io.aeyer.voidcore.ws.flow.screen.ScreenComponent;
import io.aeyer.voidcore.ws.flow.screen.ScreenText;
import io.aeyer.voidcore.ws.flow.screen.Transition;
import io.aeyer.voidcore.ws.flow.view.DocumentView;
import io.aeyer.voidcore.ws.protocol.ServerMessage;
import io.aeyer.voidcore.ws.protocol.ServerMessage.InputPrompt;
import io.aeyer.voidcore.ws.protocol.ServerMessage.Row;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Release NFO viewer. [Q] returns to the releases list, [L] opens the
 * external link and increments the download counter (live-updates every
 * peer in the releases list / view via the bus).
 *
 * <p>v1.4 PR-A5: extracted as a Screen.
 *
 * <p>v1.4 PR-B step 16: rendering + listen-link logic moved here.
 * The current release/NFO viewer reads the document row directly via
 * {@link DocumentView}, including the compatibility path for stale
 * pre-migration ids preserved in frontmatter.
 * The selected id rides
 * {@link io.aeyer.voidcore.ws.VoidCoreSession#currentReleaseId()} — set by
 * {@link ReleasesListScreen} before pushing this phase, or by
 * {@code restoreFromCurrentScreen} / {@code resolveIntent} when
 * landing here directly.
 *
 * <p>If the release gets deleted out from under the viewer (sysop
 * delete fires while we're on the NFO), {@code onEnter} sees an
 * empty {@code byId} and pops back rather than painting a broken
 * frame.
 */
@ScreenComponent
public class ReleaseViewScreen implements Screen {

    private final DocumentRepository docs;

    public ReleaseViewScreen(DocumentRepository docs) {
        this.docs = docs;
    }

    @Override public Phase phase() { return Phase.RELEASES_VIEW; }
    @Override public String name() { return "release-view"; }

    @Override
    public List<String> topics(BbsContext ctx) {
        return ScreenFeatureGate.withTopic(List.of(DocumentView.TOPIC));
    }

    @Override
    public Transition onEnter(BbsContext ctx) {
        if (!ScreenFeatureGate.ensureEnabled(ctx, InstanceFeature.RELEASES, "releases")) {
            return Transition.None.INSTANCE;
        }
        Long id = ctx.session().currentReleaseId();
        if (id == null) { ctx.pop(); return Transition.None.INSTANCE; }
        Optional<DocumentRow> maybe = currentRelease(ctx, id);
        if (maybe.isEmpty()) {
            // Release deleted while we were on it — bounce back.
            ctx.session().setCurrentReleaseId(null);
            ctx.pop();
            return Transition.None.INSTANCE;
        }
        DocumentRow doc = maybe.get();
        ctx.persistCurrentScreen("{\"kind\":\"release_nfo\",\"release_id\":" + doc.id() + "}");
        ctx.send(Frames.flow("main", 21, paintRows(doc)));
        ctx.send(new InputPrompt("keystroke", "release:", null, "QL", null));
        return Transition.None.INSTANCE;
    }

    /** NFO-body canvas width: 64 inner cols, indented 2 spaces. */
    private static final int NFO_CANVAS = 64;
    private static final int NFO_INDENT = 2;

    private static List<Row> paintRows(DocumentRow doc) {
        ReleaseFrontmatter release = ReleaseFrontmatter.from(doc.frontmatter());
        String filename = release.filename() == null ? doc.slug() : release.filename();
        ArrayList<Row> rows = new ArrayList<>();

        // Header — multi-styled, hand-built (Text element doesn't carry mixed colours).
        rows.add(Frames.colored(0,
                "  == " + filename + " · " + doc.title() + " ==",
                "bright_yellow"));
        rows.add(Frames.blank(1));

        // V5 metadata header block — only emit rows for fields that are set.
        int rowN = 2;
        rowN = ScreenText.appendMetaIfSet(rows, rowN, "artist  ", release.artist());
        rowN = ScreenText.appendMetaIfSet(rows, rowN, "year    ",
                release.year() == null ? null : String.valueOf(release.year()));
        rowN = ScreenText.appendMetaIfSet(rows, rowN, "label   ", release.label());
        rowN = ScreenText.appendMetaIfSet(rows, rowN, "catalog ", release.catalogNumber());
        rowN = ScreenText.appendMetaIfSet(rows, rowN, "genre   ", release.genre());
        boolean hadMeta = rowN > 2;
        if (hadMeta) rows.add(Frames.blank(rowN++));

        // NFO body — Flow rendered: a horizontal rule, the body as a wrapping
        // paragraph indented 2 cols (Padded), another rule. Server-side renderer
        // word-wraps the body to NFO_CANVAS; the heavy `+----+ |  | +----+` box
        // decoration of the legacy paint is gone — the rules read as the same
        // visual frame without the per-line side borders that capped lines at
        // 64 cols. Per ADR-031 / SPEC-layout.md.
        Layout body = new Layout.Flow(
                new Element.VStack(List.of(
                        new Element.Styled(new Element.Rule(), "bright_cyan"),
                        new Element.Padded(
                                new Element.Para(doc.body() == null ? "" : doc.body(), "default"),
                                NFO_INDENT),
                        new Element.Styled(new Element.Rule(), "bright_cyan")
                )),
                NFO_CANVAS + NFO_INDENT * 2);
        rows.addAll(LayoutRenderer.render(body, rowN));
        rowN = rows.size();  // advance past the body's emitted rows

        // Footer — multi-styled, hand-built again.
        rows.add(Frames.blank(rowN++));
        rows.add(Frames.row(rowN++,
                Frames.span("  downloads: ", "grey"),
                Frames.span(String.valueOf(release.downloadCount()), "bright_green", true),
                Frames.span("    external: ", "grey"),
                Frames.span(release.externalUrl() == null ? "<none>" : release.externalUrl(),
                        release.externalUrl() == null ? "dark_grey" : "bright_cyan")));
        rows.add(Frames.row(rowN,
                Frames.span("  [", "grey"),
                Frames.span("L", "bright_yellow", true),
                Frames.span("] listen / open external link    [", "grey"),
                Frames.span("Q", "bright_yellow", true),
                Frames.span("] back to releases", "grey")));
        return rows;
    }

    @Override
    public Transition onKey(BbsContext ctx, String key) {
        switch (key) {
            case "Q" -> {
                ctx.session().setCurrentReleaseId(null);
                ctx.pop();
            }
            case "L" -> handleListen(ctx);
            default -> { /* ignored */ }
        }
        return Transition.None.INSTANCE;
    }

    /**
     * [L]isten — increment the download counter atomically, open
     * the external URL on this session if set (else notify "no
     * link"), and publish {@link DocumentView#TOPIC} so every peer
     * viewing the list / NFO repaints with the fresh count.
     *
     * <p>The repo update is a single jOOQ atomic; we publish only
     * after it succeeds, so a failed write doesn't fan out a stale
     * invalidation.
     */
    private void handleListen(BbsContext ctx) {
        Long id = ctx.session().currentReleaseId();
        if (id == null) return;
        Optional<DocumentRow> maybe = currentRelease(ctx, id);
        if (maybe.isEmpty()) return;
        DocumentRow doc = maybe.get();
        ReleaseFrontmatter release = ReleaseFrontmatter.from(doc.frontmatter());

        docs.updateFrontmatterNumber(doc.id(), "download_count", release.downloadCount() + 1);
        ctx.publish(DocumentView.TOPIC);

        if (release.externalUrl() != null && !release.externalUrl().isBlank()) {
            ctx.send(new ServerMessage.EffectOpenUrl(release.externalUrl()));
        } else {
            ctx.send(Frames.notify("notifications",
                    "no external link configured for "
                            + (release.filename() == null ? doc.slug() : release.filename()),
                    "info", 2500));
        }
        // Don't manually re-paint — the bus.notify above invalidates
        // the View, default onEvent re-fires onEnter, this session
        // (subscribed to the topic) re-paints with the fresh
        // counter as part of the same cascade.
    }

    private Optional<DocumentRow> currentRelease(BbsContext ctx, long id) {
        return ctx.services().documents().byReleaseIdOrLegacyFileId(id)
                .map(doc -> {
                    if (doc.id() != id) {
                        ctx.session().setCurrentReleaseId(doc.id());
                    }
                    return doc;
                });
    }
}
