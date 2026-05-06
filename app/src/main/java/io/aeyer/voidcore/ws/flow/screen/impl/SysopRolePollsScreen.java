package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.polls.PollRepository;
import io.aeyer.voidcore.polls.PollRepository.Poll;
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
public class SysopRolePollsScreen implements Screen {

    private final PollRepository polls;

    public SysopRolePollsScreen(PollRepository polls) {
        this.polls = polls;
    }

    @Override public Phase phase() { return Phase.SYSOP_ROLE_POLLS; }
    @Override public String name() { return "sysop-role-polls"; }

    @Override
    public List<String> topics(BbsContext ctx) {
        return List.of(PollsListScreen.TOPIC);
    }

    @Override
    public Transition onEnter(BbsContext ctx) {
        if (!ctx.isSysop()) { ctx.pop(); return Transition.None.INSTANCE; }
        if (ctx.session().selectedSysopId() == null) { ctx.pop(); return Transition.None.INSTANCE; }
        ctx.session().setSelectedSysopResourceId(null);
        List<Poll> list = polls.recent(9);
        ArrayList<Row> rows = new ArrayList<>();
        rows.add(Frames.colored(0, "  == ROLE · POLLS ==", "bright_yellow"));
        rows.add(Frames.blank(1));
        rows.add(Frames.row(2,
                Frames.span("  [", "grey"),
                Frames.span("H", "bright_yellow", true),
                Frames.span("] ", "grey"),
                Frames.span("poll hub", "bright_cyan", true)));
        int rowN = 3;
        for (int i = 0; i < list.size(); i++) {
            Poll poll = list.get(i);
            rows.add(Frames.row(rowN++,
                    Frames.span("  [", "grey"),
                    Frames.span(String.valueOf(i + 1), "bright_yellow", true),
                    Frames.span("] ", "grey"),
                    Frames.span(ScreenText.truncate(poll.question(), 56), "default")));
        }
        rows.add(Frames.blank(rowN++));
        rows.add(Frames.row(rowN,
                Frames.span("  Pick poll number or [", "grey"),
                Frames.span("H", "bright_yellow", true),
                Frames.span("]ub grants   [", "grey"),
                Frames.span("Q", "bright_yellow", true),
                Frames.span("] back", "grey")));
        ctx.send(Frames.update("main", 77, rows));
        StringBuilder valid = new StringBuilder("HQ");
        for (int i = 0; i < list.size(); i++) valid.append(i + 1);
        ctx.send(new InputPrompt("keystroke", "role poll:", null, valid.toString(), null));
        return Transition.None.INSTANCE;
    }

    @Override
    public Transition onKey(BbsContext ctx, String key) {
        if (!ctx.isSysop()) return Transition.None.INSTANCE;
        if ("Q".equals(key)) { ctx.pop(); return Transition.None.INSTANCE; }
        if ("H".equals(key)) {
            ctx.session().setSelectedSysopResourceId(PollsListScreen.HUB_ID);
            ctx.push(Phase.SYSOP_ROLE_POLL);
            return Transition.None.INSTANCE;
        }
        if (key.length() == 1 && Character.isDigit(key.charAt(0))) {
            int idx = Character.digit(key.charAt(0), 10);
            List<Poll> list = polls.recent(9);
            if (idx >= 1 && idx <= list.size()) {
                ctx.session().setSelectedSysopResourceId(list.get(idx - 1).id());
                ctx.push(Phase.SYSOP_ROLE_POLL);
            }
        }
        return Transition.None.INSTANCE;
    }
}
