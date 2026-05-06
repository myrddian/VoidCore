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
 * VoidMail outbox — sent messages with basic delivery state. Reuses the
 * same per-user topic as inbox so other sessions refresh when the sender
 * writes or deletes mail.
 */
@ScreenComponent
public class NetmailOutboxScreen implements Screen {

    private final NetmailRepository netmail;
    private final AclService acl;

    public NetmailOutboxScreen(NetmailRepository netmail, AclService acl) {
        this.netmail = netmail;
        this.acl = acl;
    }

    @Override public Phase phase() { return Phase.NETMAIL_OUTBOX; }
    @Override public String name() { return "netmail-outbox"; }

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
        if (uid == null) { ctx.pop(); return Transition.None.INSTANCE; }
        if (!acl.can(ctx.session(), AclResourceType.VOIDMAIL_SYSTEM, NetmailInboxScreen.SYSTEM_ID, AclPermission.VIEW)) {
            ctx.send(Frames.notify("notifications",
                    "you do not have access to VoidMail", "warn", 3000));
            ctx.pop();
            return Transition.None.INSTANCE;
        }
        ctx.persistCurrentScreen("{\"kind\":\"netmail_outbox\"}");
        List<NetmailMessage> list = netmail.outbox(uid);

        ArrayList<Row> rows = new ArrayList<>();
        rows.add(Frames.colored(0,
                "  == VOIDMAIL OUTBOX ==   " + list.size() + " messages",
                "bright_yellow"));
        rows.add(Frames.blank(1));
        if (list.isEmpty()) {
            rows.add(Frames.colored(2, "  (no sent messages)", "dark_grey"));
        } else {
            rows.add(Frames.colored(2,
                    "      to                subject                          state",
                    "dark_grey"));
        }
        int rowN = 3;
        for (int i = 0; i < list.size(); i++) {
            NetmailMessage m = list.get(i);
            String state = m.readAt() == null ? "sent" : "read";
            rows.add(Frames.row(rowN++,
                    Frames.span("  [", "grey"),
                    Frames.span(String.valueOf(i + 1), "bright_yellow", true),
                    Frames.span("] ", "grey"),
                    Frames.span(ScreenText.padRight(m.toHandle(), 16), "bright_cyan"),
                    Frames.span(ScreenText.padRight(ScreenText.truncate(m.subject(), 32), 33), "default"),
                    Frames.span(state, m.readAt() == null ? "dark_grey" : "bright_green")));
        }
        rows.add(Frames.blank(rowN++));
        rows.add(Frames.row(rowN,
                Frames.span("  Pick a number to read   [", "grey"),
                Frames.span("I", "bright_yellow", true),
                Frames.span("] inbox   [", "grey"),
                Frames.span(canCompose(ctx) ? "W" : "-", canCompose(ctx) ? "bright_yellow" : "dark_grey", canCompose(ctx)),
                Frames.span(hasDraft(ctx) ? "] continue draft   [" : "] write new   [", "grey"),
                Frames.span("Q", "bright_yellow", true),
                Frames.span("] back to menu", "grey")));
        ctx.send(Frames.update("main", 60, rows));

        StringBuilder valid = new StringBuilder();
        for (int i = 0; i < list.size(); i++) valid.append(i + 1);
        valid.append('I');
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
        if ("I".equals(key)) {
            ctx.replaceTopAndEnter(Phase.NETMAIL_INBOX);
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
            List<NetmailMessage> list = netmail.outbox(uid);
            if (idx >= 1 && idx <= list.size()) {
                NetmailMessage m = list.get(idx - 1);
                ctx.session().setCurrentNetmailId(m.id());
                ctx.push(Phase.NETMAIL_READ);
            }
        }
        return Transition.None.INSTANCE;
    }

    private boolean canCompose(BbsContext ctx) {
        return acl.can(ctx.session(), AclResourceType.VOIDMAIL_SYSTEM, NetmailInboxScreen.SYSTEM_ID, AclPermission.POST);
    }

    private boolean hasDraft(BbsContext ctx) {
        var draft = ctx.session().netmailDraft();
        return draft != null && !draft.isEmpty();
    }
}
