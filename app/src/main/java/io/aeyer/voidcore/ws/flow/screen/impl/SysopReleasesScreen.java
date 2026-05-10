package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.acl.AclPermission;
import io.aeyer.voidcore.acl.AclResourceType;
import io.aeyer.voidcore.acl.AclService;
import io.aeyer.voidcore.documents.DocumentFilter;
import io.aeyer.voidcore.documents.DocumentRow;
import io.aeyer.voidcore.documents.ReleaseFrontmatter;
import io.aeyer.voidcore.ws.VoidCoreSession;
import io.aeyer.voidcore.ws.flow.Frames;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.flow.screen.Screen;
import io.aeyer.voidcore.ws.flow.screen.ScreenComponent;
import io.aeyer.voidcore.ws.flow.screen.ScreenText;
import io.aeyer.voidcore.ws.flow.screen.Transition;
import io.aeyer.voidcore.ws.flow.view.DocumentView;
import io.aeyer.voidcore.ws.protocol.ServerMessage.InputPrompt;
import io.aeyer.voidcore.ws.protocol.ServerMessage.Row;

import java.util.ArrayList;
import java.util.List;

/**
 * Sysop · files — list with [N]ew or pick number to edit.
 *
 * <p>Owns the {@link #clearDraft} helper that the SysopFileNew*
 * chain shares — keeps the per-session draft fields concentrated
 * in one place.
 */
@ScreenComponent
public class SysopReleasesScreen implements Screen {

    private static final String TYPE_RELEASE = "release";

    private final AclService acl;

    public SysopReleasesScreen(AclService acl) {
        this.acl = acl;
    }

    @Override public Phase phase() { return Phase.SYSOP_RELEASES; }
    @Override public String name() { return "sysop-releases"; }

    @Override
    public List<String> topics(BbsContext ctx) {
        return List.of(DocumentView.TOPIC);
    }

    @Override
    public Transition onEnter(BbsContext ctx) {
        if (!canEnter(ctx)) { ctx.pop(); return Transition.None.INSTANCE; }
        ctx.persistCurrentScreen("{\"kind\":\"sysop_releases\"}");
        var list = manageableReleases(ctx);
        ArrayList<Row> rows = new ArrayList<>();
        rows.add(Frames.colored(0, "  == SYSOP · FILES ==   " + list.size(), "bright_yellow"));
        rows.add(Frames.blank(1));
        int rowN = 2;
        for (int i = 0; i < list.size(); i++) {
            DocumentRow doc = list.get(i);
            ReleaseFrontmatter release = ReleaseFrontmatter.from(doc.frontmatter());
            String filename = release.filename() == null ? doc.slug() : release.filename();
            String url = release.externalUrl() == null
                    ? "<none>"
                    : ScreenText.truncate(release.externalUrl(), 40);
            rows.add(Frames.row(rowN++,
                    Frames.span("  [", "grey"),
                    Frames.span(String.valueOf(i + 1), "bright_yellow", true),
                    Frames.span("] ", "grey"),
                    Frames.span(ScreenText.padRight(filename, 14), "bright"),
                    Frames.span(ScreenText.padRight(ScreenText.truncate(doc.title(), 18), 20), "default"),
                    Frames.span(url, release.externalUrl() == null ? "dark_grey" : "bright_cyan")));
        }
        rows.add(Frames.blank(rowN++));
        rows.add(Frames.row(rowN,
                Frames.span("  [", "grey"),
                Frames.span(canCreate(ctx) ? "N" : "-", canCreate(ctx) ? "bright_yellow" : "dark_grey", canCreate(ctx)),
                Frames.span("] new file      pick number to edit   [", "grey"),
                Frames.span("Q", "bright_yellow", true),
                Frames.span("] back", "grey")));
        ctx.send(Frames.update("main", 74, rows));
        StringBuilder valid = new StringBuilder("Q");
        if (canCreate(ctx)) valid.append('N');
        for (int i = 0; i < list.size(); i++) valid.append(i + 1);
        ctx.send(new InputPrompt("keystroke", "file:", null, valid.toString(), null));
        return Transition.None.INSTANCE;
    }

    @Override
    public Transition onKey(BbsContext ctx, String k) {
        if (!canEnter(ctx)) return Transition.None.INSTANCE;
        if ("Q".equals(k)) { ctx.pop(); return Transition.None.INSTANCE; }
        if ("N".equals(k)) {
            if (!canCreate(ctx)) return Transition.None.INSTANCE;
            clearDraft(ctx.session());
            ctx.push(Phase.SYSOP_RELEASE_NEW);
            return Transition.None.INSTANCE;
        }
        if (k.length() == 1 && Character.isDigit(k.charAt(0))) {
            int idx = Character.digit(k.charAt(0), 10);
            var list = manageableReleases(ctx);
            if (idx < 1 || idx > list.size()) return Transition.None.INSTANCE;
            DocumentRow doc = list.get(idx - 1);
            ctx.session().setSelectedSysopId(doc.id());
            ctx.push(Phase.SYSOP_RELEASE_EDIT);
        }
        return Transition.None.INSTANCE;
    }

    private List<DocumentRow> manageableReleases(BbsContext ctx) {
        return ctx.services().documents().findByFilter(
                DocumentFilter.empty().withTypeSlug(TYPE_RELEASE),
                ctx.session(),
                0,
                9).stream()
                .filter(doc -> ctx.isSysop()
                        || acl.can(ctx.session(), AclResourceType.DOCUMENT, doc.id(), AclPermission.MANAGE))
                .toList();
    }

    private boolean canEnter(BbsContext ctx) {
        return ctx.isSysop() || !manageableReleases(ctx).isEmpty();
    }

    private boolean canCreate(BbsContext ctx) {
        return canEnter(ctx);
    }

    /**
     * Reset every {@code VoidCoreSession.pendingRelease*} field to its
     * initial state. Called when the [N]ew walk starts (to clear any
     * stale draft from a previous abandoned attempt) and when any
     * step in the walk cancels via [Esc].
     */
    public static void clearDraft(VoidCoreSession session) {
        session.setPendingReleaseFilename(null);
        session.setPendingReleaseTitle(null);
        session.setPendingReleaseExternalUrl(null);
        session.setPendingReleaseNfoLines(null);
        session.setPendingReleaseYear(null);
        session.setPendingReleaseArtist(null);
        session.setPendingReleaseLabel(null);
        session.setPendingReleaseCatalogNumber(null);
        session.setPendingReleaseGenre(null);
    }
}
