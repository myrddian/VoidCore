package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.acl.AclPermission;
import io.aeyer.voidcore.acl.AclResourceType;
import io.aeyer.voidcore.acl.AclService;
import io.aeyer.voidcore.instance.InstanceFeature;
import io.aeyer.voidcore.messages.PostRepository;
import io.aeyer.voidcore.messages.ThreadRepository;
import io.aeyer.voidcore.ws.flow.Frames;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.flow.screen.ScreenAppComponent;
import io.aeyer.voidcore.ws.flow.screen.Transition;
import io.aeyer.voidcore.ws.flow.screen.form.FieldKind;
import io.aeyer.voidcore.ws.flow.screen.form.WizardFormApp;
import io.aeyer.voidcore.ws.flow.screen.form.WizardStep;

import java.util.List;
import java.util.Optional;

/**
 * Forum compose — unified 2-step wizard replacing the legacy pair
 * {@link ComposeThreadSubjectScreen} + {@link ComposeThreadBodyScreen}.
 *
 * <p>Step 1: subject (single-line, non-empty).
 * Step 2: body (multi-line editor, non-empty).
 *
 * <p>On submit: inserts thread + first post atomically, publishes the
 * per-base topic, fires social-event hooks (thread.created + post.created),
 * and lands the writer on the new thread view.
 */
@ScreenAppComponent
public class ComposeThreadScreen extends WizardFormApp<ComposeThreadScreen.Draft> {

    static final class Draft {
        String subject = "";
        String body    = "";
    }

    private final ThreadRepository threads;
    private final PostRepository   posts;
    private final AclService acl;

    public ComposeThreadScreen(ThreadRepository threads, PostRepository posts, AclService acl) {
        this.threads = threads;
        this.posts   = posts;
        this.acl = acl;
    }

    @Override public Phase  phase() { return Phase.COMPOSE_THREAD; }
    @Override public String name()  { return "compose-thread"; }

    @Override
    public Transition onEnter(BbsContext ctx) {
        if (!ScreenFeatureGate.ensureEnabled(ctx, InstanceFeature.MESSAGE_BOARD, "message board")) {
            return Transition.None.INSTANCE;
        }
        return super.onEnter(ctx);
    }

    @Override
    protected String appKey(BbsContext ctx) { return "compose-thread"; }

    @Override
    protected Draft newState(BbsContext ctx) { return new Draft(); }

    @Override
    protected List<WizardStep<Draft>> steps(BbsContext ctx) {
        return List.of(
            new WizardStep<>(
                "Subject", FieldKind.SINGLE_LINE,
                (d, v) -> d.subject = v == null ? "" : v.trim(),
                v -> v == null || v.trim().isEmpty()
                    ? Optional.of("subject cannot be empty") : Optional.empty()),
            new WizardStep<>(
                "Body", FieldKind.MULTI_LINE,
                (d, v) -> d.body = v == null ? "" : v,
                v -> v == null || v.trim().isEmpty()
                    ? Optional.of("body cannot be empty") : Optional.empty())
        );
    }

    @Override
    protected void onSubmit(BbsContext ctx, Draft d) {
        Long uid = ctx.session().userId();
        Long bid = ctx.session().selectedBaseId();
        if (uid == null || bid == null) {
            ctx.pop();
            return;
        }
        if (!acl.can(ctx.session(), AclResourceType.MESSAGE_BASE, bid, AclPermission.POST)) {
            ctx.send(Frames.notify("notifications", "read-only board", "warn", 2500));
            ctx.pop();
            return;
        }

        long tid = threads.insert(bid, d.subject, uid);
        posts.insert(tid, uid, d.body);

        ctx.send(Frames.notify("notifications", "thread posted", "info", 2500));

        // Invalidate the per-base topic so all sessions on the threads
        // list of this base repaint with the new entry.
        ctx.publish(ThreadsListScreen.topicFor(bid));

        // #87/#89: record activity + check first-thread / first-post achievements.
        if (ctx.services().socialEvents() != null) {
            var threadAwards = ctx.services().socialEvents().recordEvent(
                    "thread.created", uid,
                    ctx.services().json().createObjectNode()
                            .put("thread_id", tid)
                            .put("base_id", bid)
                            .put("subject", d.subject));
            for (var a : threadAwards) {
                ctx.send(Frames.notify("notifications",
                        "★ Achievement unlocked: " + a.name(), "info", 4000));
            }
            var postAwards = ctx.services().socialEvents().recordEvent(
                    "post.created", uid,
                    ctx.services().json().createObjectNode()
                            .put("thread_id", tid));
            for (var a : postAwards) {
                ctx.send(Frames.notify("notifications",
                        "★ Achievement unlocked: " + a.name(), "info", 4000));
            }
        }

        // Land the writer on the new thread; WizardFormApp calls popAndExit
        // after onSubmit returns, which pops the wizard off the stack; but we
        // want to replace it with THREAD_VIEW rather than expose the threads
        // list directly, so replaceTopAndEnter swaps the slot first.
        ctx.session().setSelectedThreadId(tid);
        ctx.replaceTopAndEnter(Phase.THREAD_VIEW);
    }
}
