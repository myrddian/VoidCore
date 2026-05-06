package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.acl.AclPermission;
import io.aeyer.voidcore.acl.AclPrincipalType;
import io.aeyer.voidcore.acl.AclResourceType;
import io.aeyer.voidcore.acl.AclService;
import io.aeyer.voidcore.instance.InstanceFeature;
import io.aeyer.voidcore.polls.PollRepository;
import io.aeyer.voidcore.ws.flow.Frames;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.flow.screen.ScreenAppComponent;
import io.aeyer.voidcore.ws.flow.screen.Transition;
import io.aeyer.voidcore.ws.flow.screen.form.FieldKind;
import io.aeyer.voidcore.ws.flow.screen.form.WizardFormApp;
import io.aeyer.voidcore.ws.flow.screen.form.WizardStep;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * TUI-framework replacement for {@link PollNewQuestionScreen} +
 * {@link PollNewOptionsScreen}. Two-step {@link WizardFormApp}:
 * <ol>
 *   <li>SINGLE_LINE — question (required, max 200 chars)</li>
 *   <li>VARIABLE_LIST — options (min 2, max 9, each max 80 chars)</li>
 * </ol>
 * Blank submit on the options step triggers the min/max gate and, on
 * success, persists the poll via {@link PollRepository#insert(long, String, List)}.
 */
@ScreenAppComponent
public class PollNewScreen extends WizardFormApp<PollNewScreen.Draft> {

    static final class Draft {
        String question;
        List<String> options = new ArrayList<>();
    }

    private final PollRepository polls;
    private final AclService acl;

    /** Stash the live state so countListEntries() can read it. */
    private Draft live;

    public PollNewScreen(PollRepository polls, AclService acl) {
        this.polls = polls;
        this.acl = acl;
    }

    @Override public Phase phase()              { return Phase.POLL_NEW; }
    @Override public String name()              { return "poll-new"; }
    @Override protected String appKey(BbsContext ctx) { return "poll-new"; }

    @Override
    public Transition onEnter(BbsContext ctx) {
        if (!ScreenFeatureGate.ensureEnabled(ctx, InstanceFeature.POLLS, "polls")) {
            return Transition.None.INSTANCE;
        }
        if (!acl.can(ctx.session(), AclResourceType.POLL, PollsListScreen.HUB_ID, AclPermission.POST)) {
            ctx.send(Frames.notify("notifications",
                    "you do not have permission to create polls", "warn", 3000));
            ctx.pop();
            return Transition.None.INSTANCE;
        }
        return super.onEnter(ctx);
    }

    @Override
    protected Draft newState(BbsContext ctx) {
        live = new Draft();
        return live;
    }

    @Override
    protected int countListEntries() {
        return live == null ? 0 : live.options.size();
    }

    @Override
    protected List<WizardStep<Draft>> steps(BbsContext ctx) {
        return List.of(
            new WizardStep<>("Question", FieldKind.SINGLE_LINE,
                (d, v) -> d.question = v == null ? "" : v.trim(),
                v -> v == null || v.trim().isEmpty()
                    ? Optional.of("question cannot be empty") : Optional.empty()),
            new WizardStep<>("Options", FieldKind.VARIABLE_LIST,
                (d, v) -> {
                    if (v == null) return;
                    String t = v.trim();
                    if (t.isEmpty()) return;
                    if (d.options.size() >= 9) return;   // cap at 9
                    if (t.length() > 80) t = t.substring(0, 80);
                    d.options.add(t);
                },
                sizeStr -> {
                    int n = Integer.parseInt(sizeStr);
                    if (n < 2) return Optional.of("need at least 2 options");
                    if (n > 9) return Optional.of("max 9 options reached");
                    return Optional.empty();
                })
        );
    }

    @Override
    protected void onSubmit(BbsContext ctx, Draft d) {
        Long uid = ctx.session().userId();
        if (uid == null) return;
        long pollId = polls.insert(uid, d.question, d.options);
        acl.grant(AclResourceType.POLL, pollId, AclPermission.MANAGE, AclPrincipalType.SYSOP, null);
        acl.grant(AclResourceType.POLL, pollId, AclPermission.MANAGE, AclPrincipalType.USER, uid);
        acl.grant(AclResourceType.POLL, pollId, AclPermission.VIEW, AclPrincipalType.AUTHENTICATED, null);
        acl.grant(AclResourceType.POLL, pollId, AclPermission.POST, AclPrincipalType.AUTHENTICATED, null);
        acl.grantRoleIfPresent(AclResourceType.POLL, pollId, AclPermission.MANAGE, "ADMIN");
        acl.grantRoleIfPresent(AclResourceType.POLL, pollId, AclPermission.MANAGE, "MODERATOR");

        ctx.audit("poll_new",
            ctx.services().json().createObjectNode()
                .put("poll_id", pollId)
                .put("options", d.options.size()));

        ctx.send(Frames.notify("notifications", "poll posted", "info", 2000));

        // Bus invalidation so peers on POLLS_LIST repaint.
        ctx.publish(PollsListScreen.TOPIC);

        // Activity-feed event + first-poll achievement.
        if (ctx.services().socialEvents() != null) {
            var awarded = ctx.services().socialEvents().recordEvent(
                    "poll.created", uid,
                    ctx.services().json().createObjectNode()
                            .put("poll_id", pollId));
            for (var a : awarded) {
                ctx.send(Frames.notify("notifications",
                        "★ Achievement unlocked: " + a.name(),
                        "info", 4000));
            }
        }
    }
}
