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
import io.aeyer.voidcore.ws.flow.layout.Element;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.flow.screen.ScreenApp;
import io.aeyer.voidcore.ws.flow.screen.ScreenComponent;
import io.aeyer.voidcore.ws.flow.screen.ScreenText;
import io.aeyer.voidcore.ws.flow.screen.Transition;
import io.aeyer.voidcore.ws.flow.ui.AppEvent;
import io.aeyer.voidcore.ws.protocol.ServerMessage;

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
 *
 * <p>Second adopter of the {@code list} element, after the message board.
 * Arrows move the highlight client-side at no round-trip cost; the
 * BBS-native {@code [1-9]} still opens a thread directly, because the
 * widget only consumes the keys it handles.
 */
@ScreenComponent
public class ThreadsListScreen extends ScreenApp {

    /** Topic prefix; full topic is {@code "base:" + baseId}. */
    public static final String TOPIC_PREFIX = "base:";

    /** Widget id, echoed back on {@code list.selected}. */
    private static final String LIST_ID = "threads";

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
    @Override protected String appKey(BbsContext ctx) {
        Long bid = ctx.session().selectedBaseId();
        return "threads-list:" + (bid == null ? "none" : bid);
    }

    @Override
    public List<String> topics(BbsContext ctx) {
        Long bid = ctx.session().selectedBaseId();
        return bid == null ? ScreenFeatureGate.withTopic(List.of())
                : ScreenFeatureGate.withTopic(List.of(topicFor(bid)));
    }

    @Override
    public Transition onEnter(BbsContext ctx) {
        if (!ScreenFeatureGate.ensureEnabled(ctx, InstanceFeature.MESSAGE_BOARD, "message board")) {
            return Transition.None.INSTANCE;
        }
        MessageBase base = currentBase(ctx);
        if (base == null) {
            // No base selected, gone, or no longer readable — drop the stale
            // selection and fall back rather than rendering an empty shell.
            ctx.session().setSelectedBaseId(null);
            ctx.pop();
            return Transition.None.INSTANCE;
        }
        ctx.persistCurrentScreen(
                "{\"kind\":\"threads\",\"base_id\":" + base.id() + "}");
        return super.onEnter(ctx);
    }

    @Override
    protected Element compose(BbsContext ctx) {
        MessageBase base = currentBase(ctx);
        if (base == null) return new Element.VStack(List.of(), 0);

        List<ThreadWithUnread> list = listFor(ctx);
        boolean canPost = canPost(ctx, base);

        List<Element> children = new ArrayList<>();
        children.add(new Element.Header(base.name().toUpperCase(),
                list.size() + (list.size() == 1 ? " thread" : " threads")));
        children.add(new Element.Spacer(1));

        if (list.isEmpty()) {
            children.add(new Element.Text(
                    canPost ? "  (no threads yet — start one with [N])" : "  (no threads yet)",
                    "dark_grey"));
        } else {
            children.add(new Element.ListView(LIST_ID, items(list), selectedId(ctx, list)));
        }

        children.add(new Element.Spacer(1));
        List<Element.KeyMenu.KeyEntry> keys = new ArrayList<>();
        if (canPost) keys.add(new Element.KeyMenu.KeyEntry("N", "new thread"));
        keys.add(new Element.KeyMenu.KeyEntry("1-9", "read"));
        keys.add(new Element.KeyMenu.KeyEntry("↑↓", "move"));
        keys.add(new Element.KeyMenu.KeyEntry("Enter", "open"));
        keys.add(new Element.KeyMenu.KeyEntry("Q", "back to bases"));
        children.add(new Element.KeyMenu(keys));

        return new Element.VStack(children, 0);
    }

    private List<Element.ListView.Item> items(List<ThreadWithUnread> list) {
        List<Element.ListView.Item> items = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            ThreadWithUnread tu = list.get(i);
            BoardThread t = tu.thread();
            // The markers carried meaning in the row layout and still do:
            // [*] pinned, [!] unread. Kept as text so a themed list row
            // doesn't have to grow a status column.
            String marker = t.pinned() ? "[*] " : (tu.unread() ? "[!] " : "    ");
            String prefix = i < 9 ? "[" + (i + 1) + "] " : "    ";
            String last = t.lastPostAt() == null ? "" : t.lastPostAt().toString().substring(0, 10);
            items.add(new Element.ListView.Item(
                    String.valueOf(t.id()),
                    prefix + marker + ScreenText.truncate(t.subject(), 48)
                            + "  " + t.authorHandle(),
                    t.postCount() + " posts" + (last.isEmpty() ? "" : "  " + last)));
        }
        return items;
    }

    /** Re-highlight the thread just read, when the user comes back to the list. */
    private String selectedId(BbsContext ctx, List<ThreadWithUnread> list) {
        Long selected = ctx.session().selectedThreadId();
        if (selected == null) return null;
        return list.stream().anyMatch(tu -> tu.thread().id() == selected)
                ? String.valueOf(selected) : null;
    }

    @Override
    protected void onEvent(BbsContext ctx, AppEvent ev) {
        switch (ev) {
            case AppEvent.ListSelected ls -> {
                if (LIST_ID.equals(ls.widgetId())) open(ctx, ls.itemId());
            }
            case AppEvent.FieldCancel fc -> backToBases(ctx);
            default -> { /* no other widgets on this screen */ }
        }
    }

    @Override
    public Transition onKey(BbsContext ctx, String key) {
        if ("Q".equals(key)) {
            backToBases(ctx);
            return Transition.None.INSTANCE;
        }
        if ("N".equals(key)) {
            MessageBase base = currentBase(ctx);
            if (base == null || !canPost(ctx, base)) {
                ctx.send(Frames.notify("notifications", "read-only board", "warn", 2500));
                return Transition.None.INSTANCE;
            }
            pushAndExit(ctx, Phase.COMPOSE_THREAD);
            return Transition.None.INSTANCE;
        }
        if (key.length() == 1 && Character.isDigit(key.charAt(0))) {
            int idx = Character.digit(key.charAt(0), 10);
            List<ThreadWithUnread> list = listFor(ctx);
            if (idx >= 1 && idx <= list.size()) {
                enter(ctx, list.get(idx - 1).thread().id());
            }
        }
        return Transition.None.INSTANCE;
    }

    @Override
    public Transition onCancel(BbsContext ctx) {
        backToBases(ctx);
        return Transition.None.INSTANCE;
    }

    private void backToBases(BbsContext ctx) {
        ctx.session().setSelectedBaseId(null);
        popAndExit(ctx);
    }

    /** Selection arrives as an item id — a thread id as a string. */
    private void open(BbsContext ctx, String itemId) {
        long id;
        try {
            id = Long.parseLong(itemId);
        } catch (NumberFormatException e) {
            return;
        }
        // Re-check against the visible list rather than trusting the wire:
        // the id must belong to the base this user is actually reading.
        if (listFor(ctx).stream().noneMatch(tu -> tu.thread().id() == id)) return;
        enter(ctx, id);
    }

    private void enter(BbsContext ctx, long threadId) {
        ctx.session().setSelectedThreadId(threadId);
        pushAndExit(ctx, Phase.THREAD_VIEW);
    }

    /**
     * Keystroke prompt carrying the live digits, so single-key selection
     * keeps reaching the server alongside the list's own navigation.
     */
    @Override
    protected ServerMessage.InputPrompt defaultInputPrompt(BbsContext ctx) {
        MessageBase base = currentBase(ctx);
        StringBuilder valid = new StringBuilder(
                base != null && canPost(ctx, base) ? "NQ" : "Q");
        int threadCount = Math.min(9, listFor(ctx).size());
        for (int i = 1; i <= threadCount; i++) valid.append(i);
        return new ServerMessage.InputPrompt("keystroke", "thread:", null, valid.toString(), null);
    }

    /** The selected base, or null when it is missing or unreadable. */
    private MessageBase currentBase(BbsContext ctx) {
        Long bid = ctx.session().selectedBaseId();
        if (bid == null || ctx.session().userId() == null) return null;
        MessageBase base = bases.findById(bid).orElse(null);
        if (base == null) return null;
        return acl.can(ctx.session(), AclResourceType.MESSAGE_BASE, base.id(), AclPermission.VIEW)
                ? base : null;
    }

    private boolean canPost(BbsContext ctx, MessageBase base) {
        return acl.can(ctx.session(), AclResourceType.MESSAGE_BASE, base.id(), AclPermission.POST);
    }

    private List<ThreadWithUnread> listFor(BbsContext ctx) {
        Long bid = ctx.session().selectedBaseId();
        Long uid = ctx.session().userId();
        if (bid == null || uid == null) return List.of();
        return threads.listInBase(bid, uid);
    }
}
