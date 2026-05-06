package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.acl.AclPermission;
import io.aeyer.voidcore.acl.AclResourceType;
import io.aeyer.voidcore.acl.AclService;
import io.aeyer.voidcore.instance.InstanceFeature;
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

/**
 * Ticket #93 polls list — newest-first, numbered 1..9 for direct
 * jump into {@link PollViewScreen}; {@code [N]ew} starts the new-poll
 * walk; {@code [Q]} pops back to the menu.
 *
 * <p>Subscribes to the {@code "polls"} topic on the
 * {@link io.aeyer.voidcore.ws.flow.bus.MessageBus} so peer creates / votes
 * trigger a re-paint via the default {@code onEvent} hitting
 * {@link #onEnter}.
 */
@ScreenComponent
public class PollsListScreen implements Screen {

    /** Bus topic published on every poll create / vote / close. */
    public static final String TOPIC = "polls";
    public static final long HUB_ID = 0L;

    private static final int LIMIT = 50;

    private final PollRepository polls;
    private final AclService acl;

    public PollsListScreen(PollRepository polls, AclService acl) {
        this.polls = polls;
        this.acl = acl;
    }

    @Override public Phase phase() { return Phase.POLLS_LIST; }
    @Override public String name() { return "polls-list"; }

    @Override
    public List<String> topics(BbsContext ctx) {
        return ScreenFeatureGate.withTopic(List.of(TOPIC));
    }

    @Override
    public Transition onEnter(BbsContext ctx) {
        if (!ScreenFeatureGate.ensureEnabled(ctx, InstanceFeature.POLLS, "polls")) {
            return Transition.None.INSTANCE;
        }
        if (!acl.can(ctx.session(), AclResourceType.POLL, HUB_ID, AclPermission.VIEW)) {
            ctx.send(Frames.notify("notifications",
                    "you do not have access to polls", "warn", 3000));
            ctx.pop();
            return Transition.None.INSTANCE;
        }
        ctx.persistCurrentScreen("{\"kind\":\"polls_list\"}");
        List<Poll> list = visiblePolls(ctx);

        ArrayList<Row> rows = new ArrayList<>();
        rows.add(Frames.colored(0,
                "  == POLLS ==   " + list.size() + " total",
                "bright_yellow"));
        rows.add(Frames.blank(1));

        if (list.isEmpty()) {
            rows.add(Frames.colored(2,
                    "  (no polls available)",
                    "dark_grey"));
        } else {
            int rowN = 2;
            for (int i = 0; i < list.size(); i++) {
                Poll p = list.get(i);
                String num = (i < 9) ? "[" + (i + 1) + "]" : "   ";
                String marker = p.isOpen() ? "[ ]" : "[x]";
                String when = p.createdAt() == null
                        ? "          "
                        : p.createdAt().toLocalDate().toString();
                String author = ScreenText.padRight(p.authorHandle(), 12);
                String question = ScreenText.truncate(p.question(), 44);
                rows.add(Frames.row(rowN++,
                        Frames.span("  ", null),
                        Frames.span(num, "bright_yellow", true),
                        Frames.span(" ", null),
                        Frames.span(marker + " ",
                                p.isOpen() ? "default" : "dark_grey"),
                        Frames.span(ScreenText.padRight(question, 46), "default"),
                        Frames.span(author, "bright_cyan"),
                        Frames.span(" ", null),
                        Frames.span(when, "dark_grey")));
            }
        }
        rows.add(Frames.blank(rows.size()));
        rows.add(Frames.row(rows.size(),
                Frames.span("  pick a number to view, or [", "grey"),
                Frames.span(canCreate(ctx) ? "N" : "-", canCreate(ctx) ? "bright_yellow" : "dark_grey", canCreate(ctx)),
                Frames.span("]ew  [", "grey"),
                Frames.span("Q", "bright_yellow", true),
                Frames.span("] back", "grey")));

        ctx.send(Frames.update("main", 51, rows));

        // valid_keys: digits 1..min(9,N) + N + Q
        StringBuilder valid = new StringBuilder();
        int max = Math.min(9, list.size());
        for (int i = 1; i <= max; i++) valid.append(i);
        if (canCreate(ctx)) valid.append('N');
        valid.append('Q');
        ctx.send(new InputPrompt("keystroke", "polls:", null,
                valid.toString(), null));
        return Transition.None.INSTANCE;
    }

    @Override
    public Transition onKey(BbsContext ctx, String key) {
        switch (key) {
            case "Q" -> ctx.pop();
            case "N" -> {
                if (!canCreate(ctx)) {
                    ctx.send(Frames.notify("notifications",
                            "you do not have permission to create polls", "warn", 3000));
                    return Transition.None.INSTANCE;
                }
                // Fresh draft for the new-poll walk.
                ctx.session().setPendingPollQuestion(null);
                ctx.session().setPendingPollOptions(null);
                ctx.push(Phase.POLL_NEW);
            }
            default -> {
                if (key.length() == 1 && Character.isDigit(key.charAt(0))) {
                    int n = Character.digit(key.charAt(0), 10);
                    List<Poll> list = visiblePolls(ctx);
                    if (n >= 1 && n <= Math.min(9, list.size())) {
                        ctx.session().setCurrentPollId(list.get(n - 1).id());
                        ctx.push(Phase.POLL_VIEW);
                    }
                }
            }
        }
        return Transition.None.INSTANCE;
    }

    private List<Poll> visiblePolls(BbsContext ctx) {
        return polls.recent(LIMIT).stream()
                .filter(p -> acl.can(ctx.session(), AclResourceType.POLL, p.id(), AclPermission.VIEW))
                .toList();
    }

    private boolean canCreate(BbsContext ctx) {
        return ctx.isAuthenticated()
                && acl.can(ctx.session(), AclResourceType.POLL, HUB_ID, AclPermission.POST);
    }
}
