package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.acl.AclPermission;
import io.aeyer.voidcore.acl.AclResourceType;
import io.aeyer.voidcore.acl.AclService;
import io.aeyer.voidcore.instance.InstanceFeature;
import io.aeyer.voidcore.messages.BoardThread;
import io.aeyer.voidcore.messages.PostRepository;
import io.aeyer.voidcore.messages.ThreadRepository;
import io.aeyer.voidcore.ws.flow.Frames;
import io.aeyer.voidcore.ws.flow.layout.Element;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.flow.screen.ScreenApp;
import io.aeyer.voidcore.ws.flow.screen.ScreenAppComponent;
import io.aeyer.voidcore.ws.flow.screen.Transition;
import io.aeyer.voidcore.ws.flow.ui.AppEvent;
import io.aeyer.voidcore.ws.protocol.ServerMessage;

import java.util.List;

/**
 * Forum: reply-to-thread compose — single Editor ScreenApp (C2).
 *
 * <p>Replaces {@link ComposePostBodyScreen} with the v1.6 widget framework.
 * On EditorCommit inserts the post via PostRepository, publishes the per-thread
 * and per-base pub-sub topics, fires a social-events record, notifies the user,
 * and pops back to the thread view. [Esc] / EditorCancel cancels without saving.
 */
@ScreenAppComponent
public class ComposePostScreen extends ScreenApp {

    private final ThreadRepository threads;
    private final PostRepository posts;
    private final AclService acl;

    public ComposePostScreen(ThreadRepository threads, PostRepository posts, AclService acl) {
        this.threads = threads;
        this.posts   = posts;
        this.acl = acl;
    }

    @Override public Phase phase() { return Phase.COMPOSE_POST; }
    @Override public String name() { return "compose-post"; }

    @Override
    public Transition onEnter(BbsContext ctx) {
        if (!ScreenFeatureGate.ensureEnabled(ctx, InstanceFeature.MESSAGE_BOARD, "message board")) {
            return Transition.None.INSTANCE;
        }
        return super.onEnter(ctx);
    }

    @Override
    protected String appKey(BbsContext ctx) { return "compose-post"; }

    @Override
    protected String bannerLabel(BbsContext ctx) { return "REPLY"; }

    @Override
    protected Element compose(BbsContext ctx) {
        return new Element.VStack(List.of(
            new Element.Header("REPLY", null),
            new Element.Spacer(1),
            new Element.Form("post-form", List.of(
                new Element.Editor("body", "", "INSERT", "markdown", false)
            ), "body")
        ), 0);
    }

    @Override
    protected ServerMessage.InputPrompt defaultInputPrompt(BbsContext ctx) {
        return new ServerMessage.InputPrompt("none", null, null, null, null);
    }

    @Override
    protected void onEvent(BbsContext ctx, AppEvent ev) {
        if (ev instanceof AppEvent.EditorCommit ec && "body".equals(ec.widgetId())) {
            String body = ec.content() == null ? "" : ec.content();
            if (body.trim().isEmpty()) {
                ctx.send(Frames.notify("notifications", "post body cannot be empty", "alert", 3000));
                return;
            }
            Long uid = ctx.session().userId();
            Long tid = ctx.session().selectedThreadId();
            if (uid == null || tid == null) {
                popAndExit(ctx);
                return;
            }
            BoardThread t = threads.findById(tid).orElse(null);
            if (t == null) {
                popAndExit(ctx);
                return;
            }
            if (!acl.can(ctx.session(), AclResourceType.MESSAGE_BASE, t.baseId(), AclPermission.POST)) {
                ctx.send(Frames.notify("notifications", "read-only board", "warn", 2500));
                popAndExit(ctx);
                return;
            }
            posts.insert(tid, uid, body);
            ctx.send(Frames.notify("notifications", "posted", "info", 2000));
            if (t != null) {
                ctx.publish(ThreadsListScreen.topicFor(t.baseId()));
            }
            ctx.publish(ThreadViewScreen.topicFor(tid));
            // #87/#89: record activity + check first-post achievement.
            if (ctx.services().socialEvents() != null) {
                var awarded = ctx.services().socialEvents().recordEvent(
                        "post.created", uid,
                        ctx.services().json().createObjectNode()
                                .put("thread_id", tid));
                for (var a : awarded) {
                    ctx.send(Frames.notify("notifications",
                            "★ Achievement unlocked: " + a.name(),
                            "info", 4000));
                }
            }
            popAndExit(ctx);
        } else if (ev instanceof AppEvent.EditorCancel) {
            popAndExit(ctx);
        }
    }

    @Override
    public Transition onCancel(BbsContext ctx) {
        popAndExit(ctx);
        return Transition.None.INSTANCE;
    }
}
