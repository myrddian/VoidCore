package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.acl.AclPermission;
import io.aeyer.voidcore.acl.AclResourceType;
import io.aeyer.voidcore.acl.AclService;
import io.aeyer.voidcore.instance.InstanceFeature;
import io.aeyer.voidcore.netmail.NetmailDraft;
import io.aeyer.voidcore.netmail.NetmailMessage;
import io.aeyer.voidcore.netmail.NetmailRepository;
import io.aeyer.voidcore.ws.flow.Frames;
import io.aeyer.voidcore.ws.flow.layout.Element;
import io.aeyer.voidcore.ws.flow.layout.Layout;
import io.aeyer.voidcore.ws.flow.layout.LayoutRenderer;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.flow.screen.Screen;
import io.aeyer.voidcore.ws.flow.screen.ScreenComponent;
import io.aeyer.voidcore.ws.flow.screen.Transition;
import io.aeyer.voidcore.ws.protocol.ServerMessage.InputPrompt;
import io.aeyer.voidcore.ws.protocol.ServerMessage.Row;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Single-message VoidMail viewer. Reply / forward pre-fill the composer,
 * delete soft-deletes from this user's view, and quit simply pops back
 * to whichever list pushed the read screen.
 *
 * <p>v1.4 PR-A: extracted as a Screen.
 *
 * <p>v1.4 PR-B step 19: rendering + handlers moved here. Reads via
 * {@link NetmailRepository#findOwned(long, long)} (per-user data,
 * no singleton View); the per-user topic
 * {@code "netmail:<uid>"} keeps multi-session views in sync.
 *
 * <p>If the message was deleted out from under the viewer (another
 * session of this user clicked [D] on the inbox first, or the
 * sender hard-removed it), {@code onEnter} pops back rather than
 * painting an orphan frame.
 */
@ScreenComponent
public class NetmailReadScreen implements Screen {

    private final NetmailRepository netmail;
    private final AclService acl;

    public NetmailReadScreen(NetmailRepository netmail, AclService acl) {
        this.netmail = netmail;
        this.acl = acl;
    }

    @Override public Phase phase() { return Phase.NETMAIL_READ; }
    @Override public String name() { return "netmail-read"; }

    @Override
    public List<String> topics(BbsContext ctx) {
        Long uid = ctx.session().userId();
        return uid == null
                ? ScreenFeatureGate.withTopic(List.of(NetmailInboxScreen.ACL_TOPIC))
                : ScreenFeatureGate.withTopic(List.of(NetmailInboxScreen.topicFor(uid), NetmailInboxScreen.ACL_TOPIC));
    }

    @Override
    public Transition onEnter(BbsContext ctx) {
        if (!ScreenFeatureGate.ensureEnabled(ctx, InstanceFeature.VOIDMAIL, "VoidMail")) {
            return Transition.None.INSTANCE;
        }
        Long uid = ctx.session().userId();
        Long mid = ctx.session().currentNetmailId();
        if (uid == null || mid == null) { ctx.pop(); return Transition.None.INSTANCE; }
        if (!acl.can(ctx.session(), AclResourceType.VOIDMAIL_SYSTEM, NetmailInboxScreen.SYSTEM_ID, AclPermission.VIEW)) {
            ctx.send(Frames.notify("notifications",
                    "you do not have access to VoidMail", "warn", 3000));
            ctx.session().setCurrentNetmailId(null);
            ctx.pop();
            return Transition.None.INSTANCE;
        }
        Optional<NetmailMessage> maybe = netmail.findOwned(mid, uid);
        if (maybe.isEmpty()) {
            ctx.session().setCurrentNetmailId(null);
            ctx.pop();
            return Transition.None.INSTANCE;
        }
        NetmailMessage m = maybe.get();
        ctx.persistCurrentScreen(
                "{\"kind\":\"netmail_read\",\"id\":" + m.id() + "}");
        if (m.unread()) {
            netmail.markRead(m.id(), uid);
            // Other sessions of this user (e.g. inbox open elsewhere)
            // need to see the unread marker drop — invalidate their
            // view via the per-user topic.
            ctx.publish(NetmailInboxScreen.topicFor(uid));
        }

        // Header rows are multi-styled (label in grey + value in bold/cyan)
        // and stay hand-built. The body wraps via Flow Para so long
        // messages don't overflow the canvas.
        ArrayList<Row> rows = new ArrayList<>();
        rows.add(Frames.colored(0, "  == VOIDMAIL ==", "bright_yellow"));
        rows.add(Frames.row(1,
                Frames.span("  from   : ", "grey"),
                Frames.span(m.fromHandle(), "bright_cyan", true)));
        rows.add(Frames.row(2,
                Frames.span("  to     : ", "grey"),
                Frames.span(m.toHandle(), "default")));
        rows.add(Frames.row(3,
                Frames.span("  subject: ", "grey"),
                Frames.span(m.subject() == null ? "(no subject)" : m.subject(),
                        "default", true)));
        if (m.sentAt() != null) {
            rows.add(Frames.row(4,
                    Frames.span("  sent   : ", "grey"),
                    Frames.span(m.sentAt().toString().substring(0, 19), "dark_grey")));
        }
        rows.add(Frames.blank(rows.size()));
        int rowN = rows.size();

        String body = m.body() == null ? "" : m.body();
        Layout bodyLayout = new Layout.Flow(
                new Element.Padded(new Element.Para(body, "default"), BODY_INDENT),
                BODY_CANVAS + BODY_INDENT);
        rows.addAll(LayoutRenderer.render(bodyLayout, rowN));
        rowN = rows.size();

        boolean incoming = m.toId() == uid;

        rows.add(Frames.blank(rowN++));
        rows.add(Frames.row(rowN,
                Frames.span("  [", "grey"),
                Frames.span(incoming ? "R" : "-", incoming ? "bright_yellow" : "dark_grey", incoming),
                Frames.span("] reply   [", "grey"),
                Frames.span("F", "bright_yellow", true),
                Frames.span("] forward   [", "grey"),
                Frames.span("D", "bright_yellow", true),
                Frames.span("] delete   [", "grey"),
                Frames.span("Q", "bright_yellow", true),
                Frames.span("] back to list", "grey")));
        ctx.send(Frames.flow("main", 61, rows));
        ctx.send(new InputPrompt("keystroke", "key:", null, incoming ? "RFDQ" : "FDQ", null));
        return Transition.None.INSTANCE;
    }

    /** Body wrap canvas (matches the legacy 2-col indent). */
    private static final int BODY_CANVAS = 78;
    private static final int BODY_INDENT = 2;

    @Override
    public Transition onKey(BbsContext ctx, String key) {
        Long uid = ctx.session().userId();
        Long mid = ctx.session().currentNetmailId();
        if (uid == null) return Transition.None.INSTANCE;
        switch (key) {
            case "Q" -> {
                ctx.session().setCurrentNetmailId(null);
                ctx.pop();
            }
            case "D" -> {
                if (mid != null) {
                    netmail.softDelete(mid, uid);
                    ctx.publish(NetmailInboxScreen.topicFor(uid));
                }
                ctx.session().setCurrentNetmailId(null);
                ctx.send(Frames.notify("notifications", "deleted", "info", 2000));
                ctx.pop();
            }
            case "R" -> {
                Optional<NetmailMessage> maybe = mid == null ? Optional.empty() : netmail.findOwned(mid, uid);
                if (maybe.isEmpty() || maybe.get().toId() != uid) return Transition.None.INSTANCE;
                if (!acl.can(ctx.session(), AclResourceType.VOIDMAIL_SYSTEM, NetmailInboxScreen.SYSTEM_ID, AclPermission.POST)) {
                    ctx.send(Frames.notify("notifications",
                            "you do not have permission to write VoidMail", "warn", 3000));
                    return Transition.None.INSTANCE;
                }
                ctx.session().setNetmailDraft(replyDraft(maybe.get()));
                ctx.send(Frames.notify("notifications",
                        "reply draft loaded", "info", 2500));
                ctx.replaceTopAndEnter(Phase.NETMAIL_COMPOSE);
            }
            case "F" -> {
                Optional<NetmailMessage> maybe = mid == null ? Optional.empty() : netmail.findOwned(mid, uid);
                if (maybe.isEmpty()) return Transition.None.INSTANCE;
                if (!acl.can(ctx.session(), AclResourceType.VOIDMAIL_SYSTEM, NetmailInboxScreen.SYSTEM_ID, AclPermission.POST)) {
                    ctx.send(Frames.notify("notifications",
                            "you do not have permission to write VoidMail", "warn", 3000));
                    return Transition.None.INSTANCE;
                }
                ctx.session().setNetmailDraft(forwardDraft(maybe.get()));
                ctx.send(Frames.notify("notifications",
                        "forward draft loaded", "info", 2500));
                ctx.replaceTopAndEnter(Phase.NETMAIL_COMPOSE);
            }
            default -> { /* ignored */ }
        }
        return Transition.None.INSTANCE;
    }

    private NetmailDraft replyDraft(NetmailMessage message) {
        return new NetmailDraft(
                message.fromHandle(),
                prefixedSubject(message.subject(), "Re: "),
                quotedBody(message));
    }

    private NetmailDraft forwardDraft(NetmailMessage message) {
        return new NetmailDraft(
                null,
                prefixedSubject(message.subject(), "Fwd: "),
                quotedBody(message));
    }

    private String prefixedSubject(String subject, String prefix) {
        String base = subject == null || subject.isBlank() ? "(no subject)" : subject;
        return base.regionMatches(true, 0, prefix, 0, prefix.length()) ? base : prefix + base;
    }

    private String quotedBody(NetmailMessage message) {
        String body = message.body() == null ? "" : message.body();
        String quoted = body.lines()
                .map(line -> "> " + line)
                .reduce((left, right) -> left + "\n" + right)
                .orElse("> ");
        return "\n\n---\nFrom: " + message.fromHandle()
                + "\nSubject: " + (message.subject() == null ? "(no subject)" : message.subject())
                + "\n\n" + quoted;
    }
}
