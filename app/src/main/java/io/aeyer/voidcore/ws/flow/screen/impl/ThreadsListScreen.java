package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.acl.AclPermission;
import io.aeyer.voidcore.acl.AclResourceType;
import io.aeyer.voidcore.acl.AclService;
import io.aeyer.voidcore.instance.InstanceFeature;
import io.aeyer.voidcore.messages.BoardThread;
import io.aeyer.voidcore.messages.MessageBase;
import io.aeyer.voidcore.messages.MessageBaseRepository;
import io.aeyer.voidcore.messages.ThreadRepository;
import io.aeyer.voidcore.messages.ThreadRepository.ThreadWithUnread;
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
 * Forum: list of threads in a base. {@code [1-9]} reads a thread,
 * {@code [N]} starts a new one, {@code [Q]} returns to the bases
 * list.
 *
 * <p>v1.4 PR-B step 20: rendering moved here. Per-base list is
 * global per base, so the bus topic is keyed by base
 * ({@link #topicFor(long)} → {@code "base:<id>"}). Subscribers re-
 * paint when a writer publishes the topic — typically on new
 * thread or on a post that bumps the last-post timestamp.
 *
 * <p>Like {@code netmail-inbox}, no singleton View — per-user
 * unread markers in the join + relatively short lists make a
 * direct repo call cheaper than a multi-keyed cache.
 */
@ScreenComponent
public class ThreadsListScreen implements Screen {

    /** Topic prefix; full topic is {@code "base:" + baseId}. */
    public static final String TOPIC_PREFIX = "base:";

    public static String topicFor(long baseId) {
        return TOPIC_PREFIX + baseId;
    }

    private final MessageBaseRepository bases;
    private final ThreadRepository threads;
    private final AclService acl;

    public ThreadsListScreen(MessageBaseRepository bases, ThreadRepository threads, AclService acl) {
        this.bases = bases;
        this.threads = threads;
        this.acl = acl;
    }

    @Override public Phase phase() { return Phase.THREADS_LIST; }
    @Override public String name() { return "threads-list"; }

    @Override
    public List<String> topics(BbsContext ctx) {
        Long bid = ctx.session().selectedBaseId();
        return bid == null ? ScreenFeatureGate.withTopic(List.of()) : ScreenFeatureGate.withTopic(List.of(topicFor(bid)));
    }

    @Override
    public Transition onEnter(BbsContext ctx) {
        if (!ScreenFeatureGate.ensureEnabled(ctx, InstanceFeature.MESSAGE_BOARD, "message board")) {
            return Transition.None.INSTANCE;
        }
        Long uid = ctx.session().userId();
        Long bid = ctx.session().selectedBaseId();
        if (uid == null || bid == null) { ctx.pop(); return Transition.None.INSTANCE; }
        MessageBase base = bases.findById(bid).orElse(null);
        if (base == null) {
            ctx.session().setSelectedBaseId(null);
            ctx.pop();
            return Transition.None.INSTANCE;
        }
        if (!acl.can(ctx.session(), AclResourceType.MESSAGE_BASE, base.id(), AclPermission.VIEW)) {
            ctx.session().setSelectedBaseId(null);
            ctx.pop();
            return Transition.None.INSTANCE;
        }
        ctx.persistCurrentScreen(
                "{\"kind\":\"threads\",\"base_id\":" + bid + "}");
        List<ThreadWithUnread> list = threads.listInBase(bid, uid);
        boolean canPost = acl.can(ctx.session(), AclResourceType.MESSAGE_BASE, base.id(), AclPermission.POST);

        ArrayList<Row> rows = new ArrayList<>();
        rows.add(Frames.colored(0,
                "  == " + base.name().toUpperCase() + " ==   " + list.size() + " threads",
                "bright_yellow"));
        rows.add(Frames.blank(1));
        rows.add(Frames.colored(2,
                "      subject                                      author          posts  last",
                "dark_grey"));
        int rowN = 3;
        for (int i = 0; i < list.size(); i++) {
            ThreadWithUnread tu = list.get(i);
            BoardThread t = tu.thread();
            String last = t.lastPostAt() == null ? ""
                    : t.lastPostAt().toString().substring(0, 10);
            String marker = t.pinned() ? "[*] " : (tu.unread() ? "[!] " : "    ");
            String markerColor = t.pinned() ? "bright_red" : (tu.unread() ? "bright_yellow" : "grey");
            rows.add(Frames.row(rowN++,
                    Frames.span("  [", "grey"),
                    Frames.span(String.valueOf(i + 1), "bright_yellow", true),
                    Frames.span("] ", "grey"),
                    Frames.span(marker, markerColor),
                    Frames.span(ScreenText.padRight(ScreenText.truncate(t.subject(), 40), 42), "default"),
                    Frames.span(ScreenText.padRight(t.authorHandle(), 14), "bright_cyan"),
                    Frames.span(ScreenText.padLeft(String.valueOf(t.postCount()), 4), "default"),
                    Frames.span("  " + last, "dark_grey")));
        }
        if (list.isEmpty()) {
            rows.add(Frames.colored(rowN++,
                    "  (no threads yet — start one with [N])", "dark_grey"));
        }
        rows.add(Frames.blank(rowN++));
        rows.add(Frames.row(rowN,
                Frames.span("  [", "grey"),
                Frames.span("N", "bright_yellow", true),
                Frames.span(canPost ? "] new thread   pick to read   [" : "] read-only board   pick to read   [", "grey"),
                Frames.span("Q", "bright_yellow", true),
                Frames.span("] back to bases", "grey")));
        ctx.send(Frames.update("main", 81, rows));

        StringBuilder valid = new StringBuilder(canPost ? "NQ" : "Q");
        for (int i = 0; i < list.size(); i++) valid.append(i + 1);
        ctx.send(new InputPrompt("keystroke", "thread:", null, valid.toString(), null));
        return Transition.None.INSTANCE;
    }

    /**
     * Esc cancels back to the bases list — same shape as {@code [Q]}.
     * Provides a fallback exit when a user expects an Esc-to-go-back
     * behaviour from other screens; without this the default
     * {@link Screen#onCancel} no-op would leave them stuck.
     */
    @Override
    public Transition onCancel(BbsContext ctx) {
        ctx.session().setSelectedBaseId(null);
        ctx.pop();
        return Transition.None.INSTANCE;
    }

    @Override
    public Transition onKey(BbsContext ctx, String key) {
        if ("Q".equals(key)) {
            ctx.session().setSelectedBaseId(null);
            ctx.pop();
            return Transition.None.INSTANCE;
        }
        if ("N".equals(key)) {
            Long bid = ctx.session().selectedBaseId();
            if (bid == null || !acl.can(ctx.session(), AclResourceType.MESSAGE_BASE, bid, AclPermission.POST)) {
                ctx.send(Frames.notify("notifications", "read-only board", "warn", 2500));
                return Transition.None.INSTANCE;
            }
            ctx.push(Phase.COMPOSE_THREAD);
            return Transition.None.INSTANCE;
        }
        if (key.length() == 1 && Character.isDigit(key.charAt(0))) {
            int idx = Character.digit(key.charAt(0), 10);
            Long bid = ctx.session().selectedBaseId();
            Long uid = ctx.session().userId();
            if (bid == null || uid == null) return Transition.None.INSTANCE;
            List<ThreadWithUnread> list = threads.listInBase(bid, uid);
            if (idx >= 1 && idx <= list.size()) {
                ctx.session().setSelectedThreadId(list.get(idx - 1).thread().id());
                ctx.push(Phase.THREAD_VIEW);
            }
        }
        return Transition.None.INSTANCE;
    }
}
