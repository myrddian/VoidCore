package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.acl.AclPermission;
import io.aeyer.voidcore.acl.AclResourceType;
import io.aeyer.voidcore.acl.AclService;
import io.aeyer.voidcore.instance.InstanceFeature;
import io.aeyer.voidcore.messages.BoardThread;
import io.aeyer.voidcore.messages.Post;
import io.aeyer.voidcore.messages.PostRepository;
import io.aeyer.voidcore.messages.ThreadRepository;
import io.aeyer.voidcore.ws.flow.Frames;
import io.aeyer.voidcore.ws.flow.layout.Element;
import io.aeyer.voidcore.ws.flow.layout.Layout;
import io.aeyer.voidcore.ws.flow.layout.LayoutRenderer;
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
 * Forum: thread view with posts. {@code [R]} replies, {@code [Q]}
 * pops back to the threads list.
 *
 * <p>v1.4 PR-B step 20: rendering moved here. Per-thread topic
 * {@code "thread:<id>"} drives cross-session live update — every
 * session viewing this thread sees a new post repaint via the
 * default {@code onEvent} → {@code onEnter}.
 */
@ScreenComponent
public class ThreadViewScreen implements Screen {

    /** Topic prefix; full topic is {@code "thread:" + threadId}. */
    public static final String TOPIC_PREFIX = "thread:";

    public static String topicFor(long threadId) {
        return TOPIC_PREFIX + threadId;
    }

    private final ThreadRepository threads;
    private final PostRepository posts;
    private final AclService acl;

    public ThreadViewScreen(ThreadRepository threads, PostRepository posts, AclService acl) {
        this.threads = threads;
        this.posts = posts;
        this.acl = acl;
    }

    @Override public Phase phase() { return Phase.THREAD_VIEW; }
    @Override public String name() { return "thread-view"; }

    @Override
    public List<String> topics(BbsContext ctx) {
        Long tid = ctx.session().selectedThreadId();
        Long bid = ctx.session().selectedBaseId();
        if (tid == null) return ScreenFeatureGate.withTopic(List.of());
        return bid == null
                ? ScreenFeatureGate.withTopic(List.of(topicFor(tid)))
                : ScreenFeatureGate.withTopic(List.of(topicFor(tid), ThreadsListScreen.topicFor(bid)));
    }

    @Override
    public Transition onEnter(BbsContext ctx) {
        if (!ScreenFeatureGate.ensureEnabled(ctx, InstanceFeature.MESSAGE_BOARD, "message board")) {
            return Transition.None.INSTANCE;
        }
        Long uid = ctx.session().userId();
        Long tid = ctx.session().selectedThreadId();
        if (uid == null || tid == null) { ctx.pop(); return Transition.None.INSTANCE; }
        BoardThread t = threads.findById(tid).orElse(null);
        if (t == null) {
            ctx.session().setSelectedThreadId(null);
            ctx.pop();
            return Transition.None.INSTANCE;
        }
        if (!acl.can(ctx.session(), AclResourceType.MESSAGE_BASE, t.baseId(), AclPermission.VIEW)) {
            ctx.session().setSelectedThreadId(null);
            ctx.session().setSelectedBaseId(null);
            ctx.pop();
            return Transition.None.INSTANCE;
        }
        if (ctx.session().selectedBaseId() == null) ctx.session().setSelectedBaseId(t.baseId());
        ctx.persistCurrentScreen(
                "{\"kind\":\"thread\",\"id\":" + tid + "}");
        threads.markRead(uid, tid);
        // Mark-read affects this user's unread badge in other open
        // sessions of theirs (e.g. a peer tab on threads-list /
        // bases-list); invalidate the per-base topic so they
        // repaint with fresh unread counts.
        ctx.publish(ThreadsListScreen.topicFor(t.baseId()));

        List<Post> postList = posts.listInThread(tid);

        // Header rows + per-post separator rows are multi-styled
        // (author handle in cyan-bold within a grey row); they stay
        // hand-built. Each post body is Flow-rendered as a Para
        // indented 4 cols so long posts wrap at the canvas instead
        // of running off the right edge.
        ArrayList<Row> rows = new ArrayList<>();
        rows.add(Frames.colored(0, "  == " + ScreenText.truncate(t.subject(), 64) + " ==",
                "bright_yellow"));
        rows.add(Frames.row(1,
                Frames.span("  by ", "grey"),
                Frames.span(t.authorHandle(), "bright_cyan", true),
                Frames.span("   posts: ", "grey"),
                Frames.span(String.valueOf(t.postCount()), "default")));
        rows.add(Frames.blank(2));
        int rowN = 3;
        for (Post p : postList) {
            String when = p.postedAt() == null ? "" : p.postedAt().toString().substring(0, 16);
            rows.add(Frames.row(rowN++,
                    Frames.span("  ─── ", "dark_grey"),
                    Frames.span(p.authorHandle(), "bright_cyan", true),
                    Frames.span("  " + when, "dark_grey"),
                    Frames.span(p.edited() ? "  (edited)" : "", "dark_grey")));

            String bodyText = p.body() == null ? "" : p.body();
            Layout postBody = new Layout.Flow(
                    new Element.Padded(
                            new Element.Para(bodyText, "default"),
                            POST_INDENT),
                    POST_CANVAS + POST_INDENT);
            rows.addAll(LayoutRenderer.render(postBody, rowN));
            rowN = rows.size();
            rows.add(Frames.blank(rowN++));
        }
        rows.add(Frames.row(rowN,
                Frames.span("  [", "grey"),
                Frames.span("R", "bright_yellow", true),
                Frames.span("] reply   [", "grey"),
                Frames.span("Q", "bright_yellow", true),
                Frames.span("] back to threads", "grey")));
        ctx.send(Frames.flow("main", 82, rows));
        ctx.send(new InputPrompt("keystroke", "thread:", null, "RQ", null));
        return Transition.None.INSTANCE;
    }

    /** Post body wrap canvas (matches the legacy 4-col indent + 76-col body). */
    private static final int POST_CANVAS = 76;
    private static final int POST_INDENT = 4;

    /** Esc cancels back to the threads list — same shape as {@code [Q]}. */
    @Override
    public Transition onCancel(BbsContext ctx) {
        ctx.session().setSelectedThreadId(null);
        ctx.pop();
        return Transition.None.INSTANCE;
    }

    @Override
    public Transition onKey(BbsContext ctx, String key) {
        switch (key) {
            case "Q" -> {
                ctx.session().setSelectedThreadId(null);
                ctx.pop();
            }
            case "R" -> {
                Long bid = ctx.session().selectedBaseId();
                if (bid != null && acl.can(ctx.session(), AclResourceType.MESSAGE_BASE, bid, AclPermission.POST)) {
                    ctx.push(Phase.COMPOSE_POST);
                } else {
                    ctx.send(Frames.notify("notifications", "read-only board", "warn", 2500));
                }
            }
            default -> { /* ignored */ }
        }
        return Transition.None.INSTANCE;
    }
}
