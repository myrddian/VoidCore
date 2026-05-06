package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.documents.DocumentFilter;
import io.aeyer.voidcore.documents.DocumentRow;
import io.aeyer.voidcore.ws.flow.Frames;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.flow.screen.Screen;
import io.aeyer.voidcore.ws.flow.screen.ScreenComponent;
import io.aeyer.voidcore.ws.flow.screen.ScreenText;
import io.aeyer.voidcore.ws.flow.screen.Transition;
import io.aeyer.voidcore.ws.protocol.ServerMessage.InputPrompt;
import io.aeyer.voidcore.ws.protocol.ServerMessage.Row;

import java.util.ArrayList;
import java.util.List;

@ScreenComponent
public class SysopRoleDocumentsScreen implements Screen {

    @Override public Phase phase() { return Phase.SYSOP_ROLE_DOCUMENTS; }
    @Override public String name() { return "sysop-role-documents"; }

    @Override
    public List<String> topics(BbsContext ctx) {
        return List.of(io.aeyer.voidcore.ws.flow.view.DocumentView.TOPIC);
    }

    @Override
    public Transition onEnter(BbsContext ctx) {
        if (!ctx.isSysop()) { ctx.pop(); return Transition.None.INSTANCE; }
        if (ctx.session().selectedSysopId() == null) { ctx.pop(); return Transition.None.INSTANCE; }
        ctx.session().setSelectedSysopResourceId(null);
        List<DocumentRow> list = ctx.services().documents().findByFilter(DocumentFilter.empty(), ctx.session(), 0, 9);
        return render(ctx, list, "ROLE · DOCUMENTS", "role document:");
    }

    @Override
    public Transition onKey(BbsContext ctx, String key) {
        return pickDocument(ctx, key, ctx.services().documents().findByFilter(DocumentFilter.empty(), ctx.session(), 0, 9));
    }

    static Transition render(BbsContext ctx, List<DocumentRow> list, String title, String prompt) {
        ArrayList<Row> rows = new ArrayList<>();
        rows.add(Frames.colored(0, "  == " + title + " ==   " + list.size(), "bright_yellow"));
        rows.add(Frames.blank(1));
        int rowN = 2;
        for (int i = 0; i < list.size(); i++) {
            DocumentRow doc = list.get(i);
            rows.add(Frames.row(rowN++,
                    Frames.span("  [", "grey"),
                    Frames.span(String.valueOf(i + 1), "bright_yellow", true),
                    Frames.span("] ", "grey"),
                    Frames.span(ScreenText.padRight(doc.typeSlug(), 12), "bright_cyan", true),
                    Frames.span(ScreenText.truncate(doc.title(), 46), "default")));
        }
        rows.add(Frames.blank(rowN++));
        rows.add(Frames.row(rowN,
                Frames.span("  Pick document number to manage grants   [", "grey"),
                Frames.span("Q", "bright_yellow", true),
                Frames.span("] back", "grey")));
        ctx.send(Frames.update("main", 77, rows));
        StringBuilder valid = new StringBuilder("Q");
        for (int i = 0; i < list.size(); i++) valid.append(i + 1);
        ctx.send(new InputPrompt("keystroke", prompt, null, valid.toString(), null));
        return Transition.None.INSTANCE;
    }

    static Transition pickDocument(BbsContext ctx, String key, List<DocumentRow> list) {
        if (!ctx.isSysop()) return Transition.None.INSTANCE;
        if ("Q".equals(key)) { ctx.pop(); return Transition.None.INSTANCE; }
        if (key.length() == 1 && Character.isDigit(key.charAt(0))) {
            int idx = Character.digit(key.charAt(0), 10);
            if (idx >= 1 && idx <= list.size()) {
                ctx.session().setSelectedSysopResourceId(list.get(idx - 1).id());
                ctx.push(Phase.SYSOP_ROLE_DOCUMENT);
            }
        }
        return Transition.None.INSTANCE;
    }
}
