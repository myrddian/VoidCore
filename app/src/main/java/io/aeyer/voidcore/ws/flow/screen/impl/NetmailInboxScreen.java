package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.acl.AclPermission;
import io.aeyer.voidcore.acl.AclResourceType;
import io.aeyer.voidcore.acl.AclService;
import io.aeyer.voidcore.instance.InstanceFeature;
import io.aeyer.voidcore.netmail.NetmailMessage;
import io.aeyer.voidcore.netmail.NetmailRepository;
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
 * VoidMail inbox — keystroke list of received messages with outbox hop,
 * compose entry, and numbered read open.
 *
 * <p>v1.4 PR-A: extracted as a Screen.
 *
 * <p>v1.4 PR-B step 19: rendering moved here. Inbox data is
 * <em>per-user</em>, so unlike bulletins / files / chat there's no
 * singleton View — the screen reads
 * {@link NetmailRepository#inbox(long)} directly. The bus pattern
 * still applies: cross-session live update uses a per-user topic
 * {@code "netmail:<uid>"} so every open session of <em>this</em>
 * user repaints when a new message arrives, while other users'
 * sessions ignore the event.
 *
 * <p>{@code topics()} returns the per-user topic. Default
 * {@code onEvent} → {@code onEnter} re-queries the inbox and
 * re-paints. Re-emitting the keystroke prompt is harmless — it
 * sends the same valid-keys list.
 */
@ScreenComponent
public class NetmailInboxScreen implements Screen {

    /** Topic prefix; full topic is {@code "netmail:" + uid}. */
    public static final String TOPIC_PREFIX = "netmail:";
    public static final String ACL_TOPIC = "voidmail_acl";
    public static final long SYSTEM_ID = 1L;

    /** Build the per-user netmail topic for the given user id. */
    public static String topicFor(long userId) {
        return TOPIC_PREFIX + userId;
    }

    private final NetmailRepository netmail;
    private final AclService acl;

    public NetmailInboxScreen(NetmailRepository netmail, AclService acl) {
        this.netmail = netmail;
        this.acl = acl;
    }

    @Override public Phase phase() { return Phase.NETMAIL_INBOX; }
    @Override public String name() { return "netmail-inbox"; }

    @Override
    public List<String> topics(BbsContext ctx) {
        Long uid = ctx.session().userId();
        return uid == null
                ? ScreenFeatureGate.withTopic(List.of(ACL_TOPIC))
                : ScreenFeatureGate.withTopic(List.of(topicFor(uid), ACL_TOPIC));
    }

    @Override
    public Transition onEnter(BbsContext ctx) {
        if (!ScreenFeatureGate.ensureEnabled(ctx, InstanceFeature.VOIDMAIL, "VoidMail")) {
            return Transition.None.INSTANCE;
        }
        Long uid = ctx.session().userId();
        if (uid == null) { ctx.pop(); return Transition.None.INSTANCE; }
        if (!acl.can(ctx.session(), AclResourceType.VOIDMAIL_SYSTEM, SYSTEM_ID, AclPermission.VIEW)) {
            ctx.send(Frames.notify("notifications",
                    "you do not have access to VoidMail", "warn", 3000));
            ctx.pop();
            return Transition.None.INSTANCE;
        }
        ctx.persistCurrentScreen("{\"kind\":\"netmail_inbox\"}");
        List<NetmailMessage> list = netmail.inbox(uid);

        ArrayList<Row> rows = new ArrayList<>();
        rows.add(Frames.colored(0,
                "  == VOIDMAIL INBOX ==   " + list.size() + " messages",
                "bright_yellow"));
        rows.add(Frames.blank(1));
        if (list.isEmpty()) {
            rows.add(Frames.colored(2, "  (no messages)", "dark_grey"));
        } else {
            rows.add(Frames.colored(2,
                    "      from              subject                          when",
                    "dark_grey"));
        }
        int rowN = 3;
        for (int i = 0; i < list.size(); i++) {
            NetmailMessage m = list.get(i);
            String when = m.sentAt() == null ? ""
                    : m.sentAt().toString().substring(0, 16);
            String marker = m.unread() ? "[*] " : "    ";
            rows.add(Frames.row(rowN++,
                    Frames.span("  [", "grey"),
                    Frames.span(String.valueOf(i + 1), "bright_yellow", true),
                    Frames.span("] ", "grey"),
                    Frames.span(marker, m.unread() ? "bright_red" : "grey"),
                    Frames.span(ScreenText.padRight(m.fromHandle(), 16), "bright_cyan"),
                    Frames.span(ScreenText.padRight(ScreenText.truncate(m.subject(), 32), 33), "default"),
                    Frames.span(when, "dark_grey")));
        }
        rows.add(Frames.blank(rowN++));
        rows.add(Frames.row(rowN,
                Frames.span("  Pick a number to read   [", "grey"),
                Frames.span("O", "bright_yellow", true),
                Frames.span("] outbox   [", "grey"),
                Frames.span(canCompose(ctx) ? "W" : "-", canCompose(ctx) ? "bright_yellow" : "dark_grey", canCompose(ctx)),
                Frames.span(hasDraft(ctx) ? "] continue draft   [" : "] write new   [", "grey"),
                Frames.span("Q", "bright_yellow", true),
                Frames.span("] back to menu", "grey")));
        ctx.send(Frames.update("main", 60, rows));

        StringBuilder valid = new StringBuilder();
        for (int i = 0; i < list.size(); i++) valid.append(i + 1);
        valid.append('O');
        if (canCompose(ctx)) valid.append('W');
        valid.append('Q');
        ctx.send(new InputPrompt("keystroke", "voidmail:", null, valid.toString(), null));
        return Transition.None.INSTANCE;
    }

    @Override
    public Transition onKey(BbsContext ctx, String key) {
        Long uid = ctx.session().userId();
        if (uid == null) return Transition.None.INSTANCE;
        if ("Q".equals(key)) {
            ctx.pop();
            return Transition.None.INSTANCE;
        }
        if ("O".equals(key)) {
            ctx.replaceTopAndEnter(Phase.NETMAIL_OUTBOX);
            return Transition.None.INSTANCE;
        }
        if ("W".equals(key)) {
            if (!canCompose(ctx)) {
                ctx.send(Frames.notify("notifications",
                        "you do not have permission to write VoidMail", "warn", 3000));
                return Transition.None.INSTANCE;
            }
            ctx.push(Phase.NETMAIL_COMPOSE);
            return Transition.None.INSTANCE;
        }
        if (key.length() == 1 && Character.isDigit(key.charAt(0))) {
            int idx = Character.digit(key.charAt(0), 10);
            List<NetmailMessage> list = netmail.inbox(uid);
            if (idx >= 1 && idx <= list.size()) {
                NetmailMessage m = list.get(idx - 1);
                ctx.session().setCurrentNetmailId(m.id());
                ctx.push(Phase.NETMAIL_READ);
            }
        }
        return Transition.None.INSTANCE;
    }

    private boolean canCompose(BbsContext ctx) {
        return acl.can(ctx.session(), AclResourceType.VOIDMAIL_SYSTEM, SYSTEM_ID, AclPermission.POST);
    }

    private boolean hasDraft(BbsContext ctx) {
        var draft = ctx.session().netmailDraft();
        return draft != null && !draft.isEmpty();
    }
}
