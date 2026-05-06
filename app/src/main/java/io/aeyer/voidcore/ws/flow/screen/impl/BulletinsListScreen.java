package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.documents.DocumentKind;
import io.aeyer.voidcore.documents.DocumentRow;
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
import java.util.Comparator;
import java.util.List;

/**
 * Announcements list — pick a number to read, [Q] to back out.
 *
 * <p>v1.4 PR-A5: extracted from {@code ScreenRouter} as a Screen.
 *
 * <p>v1.4 PR-B step 15: rendering moved here and the per-session
 * {@code SessionState.bulletinsCache} slot is gone. The current
 * announcement surface reads typed article documents directly through
 * {@link DocumentView}.
 * The user's selection is captured into
 * {@link io.aeyer.voidcore.ws.VoidCoreSession#setCurrentBulletinId(Long)}
 * before {@code ctx.push(BULLETINS_VIEW)}; the view-screen reads
 * it back on enter.
 *
 * <p>{@code topics()} declares {@code "documents"} so the screen
 * re-paints when a sysop adds / pins / deletes a bulletin from
 * elsewhere — default {@code onEvent} fires {@code onEnter}, which
 * re-reads the View and redraws.
 */
@ScreenComponent
public class BulletinsListScreen implements Screen {

    @Override public Phase phase() { return Phase.BULLETINS_LIST; }
    @Override public String name() { return "bulletins-list"; }

    @Override
    public List<String> topics(BbsContext ctx) {
        return ScreenFeatureGate.withTopic(List.of(DocumentView.TOPIC));
    }

    @Override
    public Transition onEnter(BbsContext ctx) {
        if (!ScreenFeatureGate.ensureEnabled(ctx, InstanceFeature.ANNOUNCEMENTS, "announcements")) {
            return Transition.None.INSTANCE;
        }
        ctx.persistCurrentScreen("{\"kind\":\"bulletins_list\"}");
        List<DocumentRow> list = announcements(ctx);

        ArrayList<Row> rows = new ArrayList<>();
        rows.add(Frames.colored(0, "  == ANNOUNCEMENTS ==", "bright_yellow"));
        rows.add(Frames.blank(1));
        int rowN = 2;
        for (int i = 0; i < list.size(); i++) {
            DocumentRow doc = list.get(i);
            boolean pinned = isPinned(doc);
            String marker = pinned ? "[*]" : "   ";
            String date = doc.createdAt() == null ? "          "
                    : doc.createdAt().toLocalDate().toString();
            String title = ScreenText.truncate(doc.title(), 44);
            rows.add(Frames.row(rowN++,
                    Frames.span("  [", "grey"),
                    Frames.span(String.valueOf(i + 1), "bright_yellow", true),
                    Frames.span("] ", "grey"),
                    Frames.span(marker + " ", pinned ? "bright_red" : "grey"),
                    Frames.span(ScreenText.padRight(title, 46), "default"),
                    Frames.span(date, "dark_grey")));
        }
        rows.add(Frames.blank(rowN++));
        rows.add(Frames.row(rowN,
                Frames.span("  Pick a number to read, or [", "grey"),
                Frames.span("Q", "bright_yellow", true),
                Frames.span("] to return.", "grey")));

        ctx.send(Frames.update("main", 10, rows));

        // Build valid_keys: digits 1..N + Q
        StringBuilder validKeys = new StringBuilder();
        for (int i = 0; i < list.size(); i++) validKeys.append(i + 1);
        validKeys.append('Q');
        ctx.send(new InputPrompt("keystroke", "announcement:",
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
            List<DocumentRow> list = announcements(ctx);
            if (n >= 1 && n <= list.size()) {
                DocumentRow b = list.get(n - 1);
                // Stash the selection for BulletinViewScreen.onEnter.
                ctx.session().setCurrentBulletinId(b.id());
                ctx.push(Phase.BULLETINS_VIEW);
            }
        }
        return Transition.None.INSTANCE;
    }

    private static List<DocumentRow> announcements(BbsContext ctx) {
        return ctx.services().documents().list().stream()
                .filter(doc -> DocumentKind.ARTICLE.wireValue().equals(doc.typeSlug()))
                .filter(doc -> ctx.services().documents().canRead(ctx.session(), doc))
                .sorted(Comparator
                        .comparing(BulletinsListScreen::isPinned).reversed()
                        .thenComparing(DocumentRow::updatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(DocumentRow::id))
                .limit(9)
                .toList();
    }

    private static boolean isPinned(DocumentRow doc) {
        return doc.frontmatter() != null && doc.frontmatter().path("pinned").asBoolean(false);
    }
}
