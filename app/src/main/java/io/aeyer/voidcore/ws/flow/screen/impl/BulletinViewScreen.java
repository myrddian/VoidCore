package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.auth.CounterRepository;
import io.aeyer.voidcore.documents.DocumentRow;
import io.aeyer.voidcore.instance.InstanceFeature;
import io.aeyer.voidcore.ws.flow.Frames;
import io.aeyer.voidcore.ws.flow.layout.Element;
import io.aeyer.voidcore.ws.flow.layout.Layout;
import io.aeyer.voidcore.ws.flow.layout.LayoutRenderer;
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
 * Single-bulletin viewer. [Q] or [B] both pop back to the list
 * (matches v0 behaviour).
 *
 * <p>v1.4 PR-A5: extracted from {@code ScreenRouter}.
 *
 * <p>v1.4 PR-B step 15: rendering moved here. Reads via
 * {@link DocumentView}; navigation breadcrumb (which id is being
 * viewed) lives on
 * {@link io.aeyer.voidcore.ws.VoidCoreSession#currentBulletinId()}, set by
 * {@link BulletinsListScreen#onKey} before pushing.
 *
 * <p>{@code topics()} declares {@code "bulletins"} so a sysop edit
 * lands a re-paint — default {@code onEvent} re-fires
 * {@code onEnter}, which re-reads the bulletin via the View. If the
 * bulletin was deleted out from under the viewer, {@code onEnter}
 * pops back to the list rather than painting an empty frame.
 *
 * <p>The {@code {{call_no}}} body placeholder is substituted at paint
 * time from {@link CounterRepository#CALLER_COUNT} per the seed
 * welcome bulletin (SPEC §3 "Seed data").
 */
@ScreenComponent
public class BulletinViewScreen implements Screen {

    private final CounterRepository counters;

    public BulletinViewScreen(CounterRepository counters) {
        this.counters = counters;
    }

    @Override public Phase phase() { return Phase.BULLETINS_VIEW; }
    @Override public String name() { return "bulletin-view"; }

    @Override
    public List<String> topics(BbsContext ctx) {
        return ScreenFeatureGate.withTopic(List.of(DocumentView.TOPIC));
    }

    @Override
    public Transition onEnter(BbsContext ctx) {
        if (!ScreenFeatureGate.ensureEnabled(ctx, InstanceFeature.ANNOUNCEMENTS, "announcements")) {
            return Transition.None.INSTANCE;
        }
        Long id = ctx.session().currentBulletinId();
        if (id == null) {
            // Defensive: no selection — bounce back.
            ctx.pop();
            return Transition.None.INSTANCE;
        }
        Optional<DocumentRow> maybe = currentAnnouncement(ctx, id);
        if (maybe.isEmpty()) {
            // Bulletin was deleted while we were viewing it — pop
            // back rather than show an empty frame.
            ctx.session().setCurrentBulletinId(null);
            ctx.pop();
            return Transition.None.INSTANCE;
        }
        DocumentRow b = maybe.get();
        ctx.persistCurrentScreen("{\"kind\":\"bulletin\",\"id\":" + b.id() + "}");

        String body = b.body() == null ? "" : b.body();
        body = body.replace("{{call_no}}",
                String.valueOf(counters.get(CounterRepository.CALLER_COUNT)));

        // Header + body in Flow — single-style content, wraps cleanly.
        // Footer prompt row stays hand-built (multi-styled with bold).
        List<Element> children = new ArrayList<>();
        children.add(new Element.Padded(
                new Element.Text("== " + b.title(), "bright_yellow"), BODY_INDENT));
        if (b.createdAt() != null) {
            children.add(new Element.Padded(
                    new Element.Text("posted " + b.createdAt().toLocalDate(), "dark_grey"),
                    BODY_INDENT));
        }
        children.add(new Element.Spacer(1));
        children.add(new Element.Padded(new Element.Para(body, "default"), BODY_INDENT));

        ArrayList<Row> rows = new ArrayList<>(LayoutRenderer.render(new Layout.Flow(
                new Element.VStack(children),
                BODY_CANVAS + BODY_INDENT * 2)));
        int rowN = rows.size();
        rows.add(Frames.blank(rowN++));
        rows.add(Frames.row(rowN,
                Frames.span("  [", "grey"),
                Frames.span("Q", "bright_yellow", true),
                Frames.span("] back to announcement list", "grey")));

        ctx.send(Frames.flow("main", 11, rows));
        ctx.send(new InputPrompt("keystroke", "key:", null, "QB", null));
        return Transition.None.INSTANCE;
    }

    /** Body wrap canvas, mirrors the legacy hand-built indent. */
    private static final int BODY_CANVAS = 76;
    /** Two-space indent on every wrapped line. */
    private static final int BODY_INDENT = 2;

    @Override
    public Transition onKey(BbsContext ctx, String key) {
        if ("Q".equals(key) || "B".equals(key)) {
            ctx.session().setCurrentBulletinId(null);
            ctx.pop();
        }
        return Transition.None.INSTANCE;
    }

    private Optional<DocumentRow> currentAnnouncement(BbsContext ctx, long id) {
        return ctx.services().documents().byArticleIdOrLegacyBulletinId(id)
                .map(doc -> {
                    if (doc.id() != id) {
                        ctx.session().setCurrentBulletinId(doc.id());
                    }
                    return doc;
                });
    }
}
