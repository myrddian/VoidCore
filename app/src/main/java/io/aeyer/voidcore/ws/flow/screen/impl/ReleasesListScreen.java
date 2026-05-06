package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.documents.DocumentFilter;
import io.aeyer.voidcore.documents.DocumentKind;
import io.aeyer.voidcore.documents.DocumentRow;
import io.aeyer.voidcore.documents.ReleaseFrontmatter;
import io.aeyer.voidcore.instance.InstanceFeature;
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
 * Files listing — picks an entry to view its NFO, [Q] backs out.
 *
 * <p>v1.4 PR-A5: extracted as a Screen.
 *
 * <p>v1.4 PR-B step 16: rendering moved here. The current release list
 * now reads directly from {@link DocumentView} filtered to
 * {@code type_slug='release'}. The per-session
 * {@code SessionState.filesCache} slot is gone, the singleton View
 * cache invalidates on bus notify, peer re-paint goes through the
 * default {@code onEvent} → {@code onEnter} (keystroke prompt
 * re-emit is harmless, it just resends the same valid-keys list).
 *
 * <p>{@code topics()} declares {@link DocumentView#TOPIC} so a sysop
 * edit / new upload / [L]isten-driven download-counter increment
 * landing on a peer triggers a re-paint here.
 */
@ScreenComponent
public class ReleasesListScreen implements Screen {

    @Override public Phase phase() { return Phase.RELEASES_LIST; }
    @Override public String name() { return "releases-list"; }

    @Override
    public List<String> topics(BbsContext ctx) {
        return ScreenFeatureGate.withTopic(List.of(DocumentView.TOPIC));
    }

    @Override
    public Transition onEnter(BbsContext ctx) {
        if (!ScreenFeatureGate.ensureEnabled(ctx, InstanceFeature.FILES, "files")) {
            return Transition.None.INSTANCE;
        }
        // Clear any prior file selection so [L]isten can't fire on
        // a stale id if the user navigated here from a peer's view
        // that happened to set one.
        ctx.session().setCurrentReleaseId(null);
        ctx.persistCurrentScreen("{\"kind\":\"releases_list\"}");

        List<DocumentRow> list = releases(ctx);
        ArrayList<Row> rows = new ArrayList<>();
        rows.add(Frames.colored(0,
                "  == FILES ==",
                "bright_yellow"));
        rows.add(Frames.blank(1));
        rows.add(Frames.colored(2,
                "      filename       title                              size      DLs",
                "dark_grey"));
        int rowN = 3;
        for (int i = 0; i < list.size(); i++) {
            DocumentRow doc = list.get(i);
            ReleaseFrontmatter release = ReleaseFrontmatter.from(doc.frontmatter());
            String size = ScreenText.humanSize(release.sizeBytes());
            String title = ScreenText.truncate(doc.title(), 32);
            String filename = release.filename() == null ? doc.slug() : release.filename();
            rows.add(Frames.row(rowN++,
                    Frames.span("  [", "grey"),
                    Frames.span(String.valueOf(i + 1), "bright_yellow", true),
                    Frames.span("] ", "grey"),
                    Frames.span(ScreenText.padRight(filename, 14), "bright"),
                    Frames.span(" ", null),
                    Frames.span(ScreenText.padRight(title, 34), "default"),
                    Frames.span(ScreenText.padLeft(size, 7), "grey"),
                    Frames.span("  ", null),
                    Frames.span(ScreenText.padLeft(String.valueOf(release.downloadCount()), 4),
                            "bright_green")));
        }
        rows.add(Frames.blank(rowN++));
        rows.add(Frames.row(rowN,
                Frames.span("  Pick a number to read the file NFO, or [", "grey"),
                Frames.span("Q", "bright_yellow", true),
                Frames.span("] to return.", "grey")));

        ctx.send(Frames.update("main", 20, rows));

        StringBuilder validKeys = new StringBuilder();
        for (int i = 0; i < list.size(); i++) validKeys.append(i + 1);
        validKeys.append('Q');
        ctx.send(new InputPrompt("keystroke", "file:",
                null, validKeys.toString(), null));
        return Transition.None.INSTANCE;
    }

    @Override
    public Transition onKey(BbsContext ctx, String key) {
        if ("Q".equals(key)) {
            ctx.pop();
            return Transition.None.INSTANCE;
        }
        if (key.length() == 1 && Character.isDigit(key.charAt(0))) {
            int n = Character.digit(key.charAt(0), 10);
            List<DocumentRow> list = releases(ctx);
            if (n >= 1 && n <= list.size()) {
                DocumentRow doc = list.get(n - 1);
                ctx.session().setCurrentReleaseId(doc.id());
                ctx.push(Phase.RELEASES_VIEW);
            }
        }
        return Transition.None.INSTANCE;
    }

    private static List<DocumentRow> releases(BbsContext ctx) {
        return ctx.services().documents().findByFilter(
                DocumentFilter.empty().withKind(DocumentKind.RELEASE),
                ctx.session(),
                0,
                9);
    }
}
