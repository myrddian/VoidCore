package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.messages.MessageBase;
import io.aeyer.voidcore.messages.MessageBaseRepository;
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
public class SysopRoleMessageBasesScreen implements Screen {

    private final MessageBaseRepository bases;

    public SysopRoleMessageBasesScreen(MessageBaseRepository bases) {
        this.bases = bases;
    }

    @Override public Phase phase() { return Phase.SYSOP_ROLE_MESSAGE_BASES; }
    @Override public String name() { return "sysop-role-message-bases"; }

    @Override
    public Transition onEnter(BbsContext ctx) {
        if (!ctx.isSysop()) { ctx.pop(); return Transition.None.INSTANCE; }
        if (ctx.session().selectedSysopId() == null) { ctx.pop(); return Transition.None.INSTANCE; }
        ctx.session().setSelectedSysopResourceId(null);

        List<MessageBase> list = bases.listAll();
        ArrayList<Row> rows = new ArrayList<>();
        rows.add(Frames.colored(0, "  == ROLE · MESSAGE BOARDS ==   " + list.size(), "bright_yellow"));
        rows.add(Frames.blank(1));
        int rowN = 2;
        for (int i = 0; i < list.size(); i++) {
            MessageBase base = list.get(i);
            rows.add(Frames.row(rowN++,
                    Frames.span("  [", "grey"),
                    Frames.span(String.valueOf(i + 1), "bright_yellow", true),
                    Frames.span("] ", "grey"),
                    Frames.span(ScreenText.padRight(base.slug(), 14), "bright_cyan", true),
                    Frames.span(base.name(), "default")));
        }
        rows.add(Frames.blank(rowN++));
        rows.add(Frames.row(rowN,
                Frames.span("  Pick board number to manage grants   [", "grey"),
                Frames.span("Q", "bright_yellow", true),
                Frames.span("] back", "grey")));
        ctx.send(Frames.update("main", 77, rows));

        StringBuilder valid = new StringBuilder("Q");
        for (int i = 0; i < list.size(); i++) valid.append(i + 1);
        ctx.send(new InputPrompt("keystroke", "role board:", null, valid.toString(), null));
        return Transition.None.INSTANCE;
    }

    @Override
    public Transition onKey(BbsContext ctx, String key) {
        if (!ctx.isSysop()) return Transition.None.INSTANCE;
        if ("Q".equals(key)) { ctx.pop(); return Transition.None.INSTANCE; }
        if (key.length() == 1 && Character.isDigit(key.charAt(0))) {
            int idx = Character.digit(key.charAt(0), 10);
            var list = bases.listAll();
            if (idx >= 1 && idx <= list.size()) {
                ctx.session().setSelectedSysopResourceId(list.get(idx - 1).id());
                ctx.push(Phase.SYSOP_ROLE_MESSAGE_BASE);
            }
        }
        return Transition.None.INSTANCE;
    }
}
