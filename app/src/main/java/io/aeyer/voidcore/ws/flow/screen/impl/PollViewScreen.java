package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.acl.AclPermission;
import io.aeyer.voidcore.acl.AclResourceType;
import io.aeyer.voidcore.acl.AclService;
import io.aeyer.voidcore.instance.InstanceFeature;
import io.aeyer.voidcore.polls.PollRepository;
import io.aeyer.voidcore.polls.PollRepository.OptionTally;
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
import java.util.Optional;

/**
 * Ticket #93 polls — single poll detail.
 *
 * <p>Renders the question, each option with a vote-bar tally, and
 * marks the user's current pick with {@code ►}. Voting is a single
 * digit press 1..N; subsequent presses replace the prior vote
 * (single-choice). Author + sysop see {@code [C]lose} to lock the
 * poll; {@code [Q]} returns to the list.
 *
 * <p>Subscribes to {@link PollsListScreen#TOPIC} so a peer's vote
 * triggers a re-paint via the default {@code onEvent} → {@code onEnter}.
 */
@ScreenComponent
public class PollViewScreen implements Screen {

    /** Visual width (chars) of the per-option ASCII bar. */
    private static final int BAR_WIDTH = 30;

    private final PollRepository polls;
    private final AclService acl;

    public PollViewScreen(PollRepository polls, AclService acl) {
        this.polls = polls;
        this.acl = acl;
    }

    @Override public Phase phase() { return Phase.POLL_VIEW; }
    @Override public String name() { return "poll-view"; }

    @Override
    public List<String> topics(BbsContext ctx) {
        return ScreenFeatureGate.withTopic(List.of(PollsListScreen.TOPIC));
    }

    @Override
    public Transition onEnter(BbsContext ctx) {
        if (!ScreenFeatureGate.ensureEnabled(ctx, InstanceFeature.POLLS, "polls")) {
            return Transition.None.INSTANCE;
        }
        Long pollId = ctx.session().currentPollId();
        if (pollId == null) {
            ctx.pop();
            return Transition.None.INSTANCE;
        }
        Optional<Poll> maybe = polls.findById(pollId);
        if (maybe.isEmpty()) {
            // Deleted while viewing — bounce.
            ctx.session().setCurrentPollId(null);
            ctx.send(Frames.notify("notifications",
                    "poll no longer exists", "warn", 2500));
            ctx.pop();
            return Transition.None.INSTANCE;
        }
        Poll poll = maybe.get();
        if (!acl.can(ctx.session(), AclResourceType.POLL, poll.id(), AclPermission.VIEW)) {
            ctx.send(Frames.notify("notifications",
                    "you do not have access to that poll", "warn", 2500));
            ctx.session().setCurrentPollId(null);
            ctx.pop();
            return Transition.None.INSTANCE;
        }
        List<OptionTally> tallies = polls.tallies(pollId);
        long total = tallies.stream().mapToLong(OptionTally::votes).sum();
        Long uid = ctx.session().userId();
        Long myOption = (uid == null)
                ? null
                : polls.userVoteOption(pollId, uid).orElse(null);
        boolean canClose = uid != null
                && acl.can(ctx.session(), AclResourceType.POLL, poll.id(), AclPermission.MANAGE)
                && poll.isOpen();
        boolean canVote = uid != null
                && poll.isOpen()
                && acl.can(ctx.session(), AclResourceType.POLL, poll.id(), AclPermission.POST);

        ctx.persistCurrentScreen(
                "{\"kind\":\"poll\",\"id\":" + poll.id() + "}");

        ArrayList<Row> rows = new ArrayList<>();
        String header = "  == POLL ==   "
                + (poll.isOpen() ? "open" : "closed")
                + "   " + total + " vote" + (total == 1 ? "" : "s");
        rows.add(Frames.colored(0, header, "bright_yellow"));
        rows.add(Frames.blank(1));
        rows.add(Frames.row(2,
                Frames.span("  by ", "grey"),
                Frames.span(poll.authorHandle(), "bright_cyan"),
                Frames.span("  ·  ", "dark_grey"),
                Frames.span(poll.createdAt() == null
                        ? ""
                        : poll.createdAt().toLocalDate().toString(),
                        "dark_grey")));
        rows.add(Frames.blank(3));
        // The question — wrap simply at 70 chars; v1 polls keep it short.
        rows.add(Frames.colored(4, "  " + ScreenText.truncate(poll.question(), 70),
                "default"));
        rows.add(Frames.blank(5));

        int rowN = 6;
        for (int i = 0; i < tallies.size(); i++) {
            OptionTally t = tallies.get(i);
            boolean mine = myOption != null && myOption == t.optionId();
            String num = (i < 9) ? "[" + (i + 1) + "]" : "   ";
            String marker = mine ? "►" : " ";
            String text = ScreenText.padRight(t.text(), 32);
            String bar = renderBar(t.votes(), total);
            String count = String.format("%3d", t.votes());
            rows.add(Frames.row(rowN++,
                    Frames.span("  ", null),
                    Frames.span(num, "bright_yellow", true),
                    Frames.span(" ", null),
                    Frames.span(marker + " ", mine ? "bright_green" : "grey"),
                    Frames.span(text, "default"),
                    Frames.span(" ", null),
                    Frames.span(bar, mine ? "bright_green" : "bright_cyan"),
                    Frames.span(" ", null),
                    Frames.span(count, "grey")));
        }

        rows.add(Frames.blank(rowN++));
        ArrayList<io.aeyer.voidcore.ws.protocol.ServerMessage.Span> footer =
                new ArrayList<>();
        footer.add(Frames.span("  ", null));
        if (canVote) {
            footer.add(Frames.span("[", "grey"));
            footer.add(Frames.span("1-" + Math.min(9, tallies.size()),
                    "bright_yellow", true));
            footer.add(Frames.span("] vote   ", "grey"));
        } else {
            footer.add(Frames.span("(closed) ", "dark_grey"));
        }
        if (canClose) {
            footer.add(Frames.span("[", "grey"));
            footer.add(Frames.span("C", "bright_yellow", true));
            footer.add(Frames.span("]lose   ", "grey"));
        }
        footer.add(Frames.span("[", "grey"));
        footer.add(Frames.span("Q", "bright_yellow", true));
        footer.add(Frames.span("] back", "grey"));
        rows.add(Frames.row(rowN, footer.toArray(
                new io.aeyer.voidcore.ws.protocol.ServerMessage.Span[0])));

        ctx.send(Frames.update("main", 52, rows));

        StringBuilder valid = new StringBuilder();
        if (canVote) {
            int max = Math.min(9, tallies.size());
            for (int i = 1; i <= max; i++) valid.append(i);
        }
        if (canClose) valid.append('C');
        valid.append('Q');
        ctx.send(new InputPrompt("keystroke", "poll:",
                null, valid.toString(), null));
        return Transition.None.INSTANCE;
    }

    @Override
    public Transition onKey(BbsContext ctx, String key) {
        Long pollId = ctx.session().currentPollId();
        if (pollId == null) {
            ctx.pop();
            return Transition.None.INSTANCE;
        }
        switch (key) {
            case "Q" -> {
                ctx.session().setCurrentPollId(null);
                ctx.pop();
            }
            case "C" -> handleClose(ctx, pollId);
            default -> {
                if (key.length() == 1 && Character.isDigit(key.charAt(0))) {
                    handleVote(ctx, pollId, Character.digit(key.charAt(0), 10));
                }
            }
        }
        return Transition.None.INSTANCE;
    }

    private void handleVote(BbsContext ctx, long pollId, int n) {
        Long uid = ctx.session().userId();
        if (uid == null) return;
        Optional<Poll> maybe = polls.findById(pollId);
        if (maybe.isEmpty() || !maybe.get().isOpen()) {
            ctx.send(Frames.notify("notifications",
                    "poll is closed", "warn", 2000));
            return;
        }
        if (!acl.can(ctx.session(), AclResourceType.POLL, pollId, AclPermission.POST)) {
            ctx.send(Frames.notify("notifications",
                    "you do not have permission to vote on that poll", "warn", 2000));
            return;
        }
        List<OptionTally> tallies = polls.tallies(pollId);
        if (n < 1 || n > Math.min(9, tallies.size())) return;
        long optionId = tallies.get(n - 1).optionId();
        polls.vote(pollId, optionId, uid);
        ctx.send(Frames.notify("notifications",
                "voted: " + ScreenText.truncate(tallies.get(n - 1).text(), 40),
                "info", 1500));
        ctx.publish(PollsListScreen.TOPIC);
        // Repaint immediately for the writer; bus delivery handles peers.
        onEnter(ctx);
    }

    private void handleClose(BbsContext ctx, long pollId) {
        Long uid = ctx.session().userId();
        if (uid == null) return;
        Optional<Poll> maybe = polls.findById(pollId);
        if (maybe.isEmpty()) return;
        Poll poll = maybe.get();
        if (!acl.can(ctx.session(), AclResourceType.POLL, pollId, AclPermission.MANAGE)) return;
        if (!poll.isOpen()) return;
        polls.close(pollId);
        if (ctx.isSysop()) {
            ctx.services().audit(ctx.session(), "poll_close",
                    ctx.services().json().createObjectNode()
                            .put("poll_id", pollId));
        }
        ctx.send(Frames.notify("notifications",
                "poll closed", "info", 2000));
        ctx.publish(PollsListScreen.TOPIC);
        onEnter(ctx);
    }

    /**
     * Render an ASCII bar of {@code votes / total} width
     * {@link #BAR_WIDTH}. Total 0 → empty bar.
     */
    private static String renderBar(long votes, long total) {
        if (total <= 0) return "·".repeat(BAR_WIDTH);
        int filled = (int) Math.round((double) votes / total * BAR_WIDTH);
        if (filled < 0) filled = 0;
        if (filled > BAR_WIDTH) filled = BAR_WIDTH;
        return "█".repeat(filled) + "·".repeat(BAR_WIDTH - filled);
    }
}
