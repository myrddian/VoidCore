package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.acl.AclPermission;
import io.aeyer.voidcore.acl.AclResourceType;
import io.aeyer.voidcore.acl.AclService;
import io.aeyer.voidcore.auth.UserRepository;
import io.aeyer.voidcore.auth.UserRepository.UserRow;
import io.aeyer.voidcore.instance.InstanceFeature;
import io.aeyer.voidcore.netmail.NetmailDraft;
import io.aeyer.voidcore.netmail.NetmailRepository;
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
 * N1: Unified voidmail compose wizard — To / Subject / Body in a single
 * {@link WizardFormApp}, replacing the three legacy linear screens
 * ({@code NetmailComposeToScreen}, {@code NetmailComposeSubjectScreen},
 * {@code NetmailComposeBodyScreen}).
 *
 * <p>The "To" step validates the recipient handle via
 * {@link UserRepository#findByHandle(String)} and stashes the resolved
 * user-id so the submit path does not need a second lookup. On submit
 * it re-resolves the handle (guard against deletion between steps),
 * inserts the message, publishes the recipient's inbox topic, and fires
 * a targeted notification — replicating the legacy body-screen submit
 * path exactly.
 */
@ScreenAppComponent
public class NetmailComposeScreen extends WizardFormApp<NetmailComposeScreen.Draft> {

    static final class Draft {
        Long toUserId;
        String to;
        String subject;
        String body;
    }

    private final NetmailRepository netmail;
    private final UserRepository users;
    private final AclService acl;

    public NetmailComposeScreen(NetmailRepository netmail, UserRepository users, AclService acl) {
        this.netmail = netmail;
        this.users   = users;
        this.acl = acl;
    }

    @Override public Phase  phase() { return Phase.NETMAIL_COMPOSE; }
    @Override public String name()  { return "netmail-compose"; }

    @Override protected String appKey(BbsContext ctx)  { return "netmail-compose"; }
    @Override
    protected Draft newState(BbsContext ctx) {
        Draft d = new Draft();
        NetmailDraft draft = ctx.session().netmailDraft();
        if (draft != null) {
            d.to = blankToNull(draft.toHandle());
            d.subject = blankToNull(draft.subject());
            d.body = blankToNull(draft.body());
            if (d.to != null) {
                d.toUserId = users.findByHandle(d.to).map(UserRow::id).orElse(null);
            }
        }
        return d;
    }

    @Override
    public List<String> topics(BbsContext ctx) {
        return ScreenFeatureGate.withTopic(List.of(NetmailInboxScreen.ACL_TOPIC));
    }

    @Override
    public Transition onEnter(BbsContext ctx) {
        if (!ScreenFeatureGate.ensureEnabled(ctx, InstanceFeature.VOIDMAIL, "VoidMail")) {
            return Transition.None.INSTANCE;
        }
        if (!acl.can(ctx.session(), AclResourceType.VOIDMAIL_SYSTEM, NetmailInboxScreen.SYSTEM_ID, AclPermission.POST)) {
            ctx.send(Frames.notify("notifications",
                    "you do not have permission to write VoidMail", "warn", 3000));
            ctx.pop();
            return Transition.None.INSTANCE;
        }
        return super.onEnter(ctx);
    }

    @Override
    protected int initialStepIndex(BbsContext ctx, Draft state, List<WizardStep<Draft>> steps) {
        if (blank(state.to)) return 0;
        if (blank(state.subject)) return 1;
        return 2;
    }

    @Override
    protected String initialValue(BbsContext ctx, Draft state, WizardStep<Draft> step, int stepIndex) {
        return switch (stepIndex) {
            case 0 -> state.to == null ? "" : state.to;
            case 1 -> state.subject == null ? "" : state.subject;
            case 2 -> state.body == null ? "" : state.body;
            default -> "";
        };
    }

    @Override
    protected void onStateChanged(BbsContext ctx, Draft state, int stepIndex) {
        saveDraft(ctx, state);
    }

    @Override
    protected void onSnapshot(BbsContext ctx, Draft state, int stepIndex, String content) {
        if (stepIndex == 2) {
            state.body = content == null ? "" : content;
            saveDraft(ctx, state);
        }
    }

    @Override
    protected void onAbandon(BbsContext ctx, Draft state) {
        if (isEmpty(state)) {
            ctx.session().setNetmailDraft(null);
            return;
        }
        saveDraft(ctx, state);
        ctx.send(Frames.notify("notifications",
                "voidmail draft saved", "info", 2500));
    }

    @Override
    protected String bannerLabel(BbsContext ctx, Draft state, int step, int total) {
        return "VOIDMAIL-COMPOSE · step " + (step + 1) + "/" + total;
    }

    @Override
    protected String headerTitle(BbsContext ctx, Draft state, WizardStep<Draft> step, int stepIndex, int total) {
        return "VOIDMAIL";
    }

    @Override
    protected String headerRightAnnotation(BbsContext ctx, Draft state, WizardStep<Draft> step, int stepIndex, int total) {
        return step.label();
    }

    @Override
    protected List<WizardStep<Draft>> steps(BbsContext ctx) {
        return List.of(
            new WizardStep<>("To", FieldKind.SINGLE_LINE,
                (d, v) -> {
                    d.to = v == null ? "" : v.trim();
                    d.toUserId = users.findByHandle(d.to).map(UserRow::id).orElse(null);
                },
                v -> {
                    if (v == null || v.isBlank())
                        return Optional.of("recipient cannot be empty");
                    if (users.findByHandle(v.trim()).isEmpty())
                        return Optional.of("no such user: " + v.trim());
                    return Optional.empty();
                }),

            new WizardStep<>("Subject", FieldKind.SINGLE_LINE,
                (d, v) -> d.subject = (v == null || v.trim().isEmpty()) ? "(no subject)" : v.trim(),
                v -> Optional.empty()),   // empty subject becomes "(no subject)" — BBS tradition

            new WizardStep<>("Body", FieldKind.MULTI_LINE,
                (d, v) -> d.body = v == null ? "" : v,
                v -> (v == null || v.trim().isEmpty())
                    ? Optional.of("body cannot be empty")
                    : Optional.empty())
        );
    }

    @Override
    protected void onSubmit(BbsContext ctx, Draft d) {
        Long uid = ctx.session().userId();
        if (uid == null || d.to == null) {
            // Defensive: no sender or no recipient — silently discard.
            return;
        }

        // Re-resolve recipient in case they were deleted between steps
        // (matches legacy NetmailComposeBodyScreen.onLine guard).
        UserRow recipient = users.findByHandle(d.to).orElse(null);
        if (recipient == null) {
            ctx.send(Frames.notify("notifications",
                    "recipient no longer exists", "alert", 3000));
            return;
        }

        String subject = d.subject == null ? "(no subject)" : d.subject;
        String body    = d.body    == null ? ""             : d.body;

        netmail.insert(uid, recipient.id(), subject, body);

        ctx.session().setNetmailDraft(null);
        ctx.send(Frames.notify("notifications",
                "sent to " + recipient.handle(), "info", 2500));

        ctx.publish(NetmailInboxScreen.topicFor(uid));
        // Bus invalidation for any open inbox session of the recipient.
        ctx.publish(NetmailInboxScreen.topicFor(recipient.id()));

        // Targeted popup for recipient sessions on a different phase
        // (ADR-027 targeted-notify shape — mirrors legacy body screen).
        String senderHandle = users.findById(uid).map(UserRow::handle).orElse("?");
        ctx.services().mentions().notifyUser(
                recipient.id(), Phase.NETMAIL_INBOX,
                "new voidmail from " + senderHandle, 4000);

        // #87/#89: activity + achievement check (mirrors legacy body screen).
        if (ctx.services().socialEvents() != null) {
            var awarded = ctx.services().socialEvents().recordEvent(
                    "netmail.sent", uid,
                    ctx.services().json().createObjectNode()
                            .put("recipient_id", recipient.id()));
            for (var a : awarded) {
                ctx.send(Frames.notify("notifications",
                        "★ Achievement unlocked: " + a.name(),
                        "info", 4000));
            }
        }
    }

    private void saveDraft(BbsContext ctx, Draft d) {
        NetmailDraft draft = new NetmailDraft(d.to, d.subject, d.body);
        ctx.session().setNetmailDraft(draft.isEmpty() ? null : draft);
    }

    private boolean isEmpty(Draft d) {
        return blank(d.to) && blank(d.subject) && blank(d.body);
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private String blankToNull(String value) {
        return blank(value) ? null : value;
    }
}
