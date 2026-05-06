package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.acl.AclPermission;
import io.aeyer.voidcore.acl.AclResourceType;
import io.aeyer.voidcore.acl.AclService;
import io.aeyer.voidcore.instance.InstanceFeature;
import io.aeyer.voidcore.messages.MessageBase;
import io.aeyer.voidcore.messages.MessageBaseRepository;
import io.aeyer.voidcore.messages.MessageBaseRepository.BaseWithUnread;
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
 * Forum: list of message-board areas. {@code [1-9]} enters a board;
 * {@code [Q]} returns to the menu.
 *
 * <p>v1.4 PR-B step 20: rendering moved here. Bases data is global
 * but rarely-changing; per-user unread counts are joined at query
 * time so caching at a singleton View buys little. Direct
 * {@code repo.listAllWithUnread(uid)} per paint matches the
 * {@code netmail-inbox} pattern.
 */
@ScreenComponent
public class BasesListScreen implements Screen {

    public static final String TOPIC = "message_bases";

    private final MessageBaseRepository repo;
    private final AclService acl;

    public BasesListScreen(MessageBaseRepository repo, AclService acl) {
        this.repo = repo;
        this.acl = acl;
    }

    @Override public Phase phase() { return Phase.BASES_LIST; }
    @Override public String name() { return "bases-list"; }

    @Override
    public List<String> topics(BbsContext ctx) {
        return ScreenFeatureGate.withTopic(List.of(TOPIC));
    }

    @Override
    public Transition onEnter(BbsContext ctx) {
        if (!ScreenFeatureGate.ensureEnabled(ctx, InstanceFeature.MESSAGE_BOARD, "message board")) {
            return Transition.None.INSTANCE;
        }
        Long uid = ctx.session().userId();
        if (uid == null) { ctx.pop(); return Transition.None.INSTANCE; }
        ctx.persistCurrentScreen("{\"kind\":\"bases\"}");
        List<BaseWithUnread> list = listFor(ctx, uid);

        ArrayList<Row> rows = new ArrayList<>();
        rows.add(Frames.colored(0, "  == MESSAGE BOARD ==", "bright_yellow"));
        rows.add(Frames.blank(1));
        rows.add(Frames.colored(2,
                "      slug         name                                  unread",
                "dark_grey"));
        int rowN = 3;
        for (int i = 0; i < list.size(); i++) {
            BaseWithUnread bu = list.get(i);
            String unread = bu.unread() == 0 ? "" : "[" + bu.unread() + "]";
            rows.add(Frames.row(rowN++,
                    Frames.span("  [", "grey"),
                    Frames.span(String.valueOf(i + 1), "bright_yellow", true),
                    Frames.span("] ", "grey"),
                    Frames.span(ScreenText.padRight(bu.base().slug(), 13), "bright_cyan"),
                    Frames.span(ScreenText.padRight(ScreenText.truncate(bu.base().name(), 38), 39), "default"),
                    Frames.span(ScreenText.padLeft(unread, 6),
                            bu.unread() > 0 ? "bright_red" : "dark_grey")));
        }
        rows.add(Frames.blank(rowN++));
        rows.add(Frames.row(rowN,
                Frames.span("  Pick a number to enter a board, [", "grey"),
                Frames.span("Q", "bright_yellow", true),
                Frames.span("] back to menu", "grey")));
        ctx.send(Frames.update("main", 80, rows));

        StringBuilder valid = new StringBuilder("Q");
        for (int i = 0; i < list.size(); i++) valid.append(i + 1);
        ctx.send(new InputPrompt("keystroke", "board:", null, valid.toString(), null));
        return Transition.None.INSTANCE;
    }

    /**
     * Esc cancels back to the menu — same shape as {@code [Q]}.
     * Fallback exit if a user types Esc instead of Q (or if the
     * keystroke prompt isn't accepting Q for any reason).
     */
    @Override
    public Transition onCancel(BbsContext ctx) {
        ctx.pop();
        return Transition.None.INSTANCE;
    }

    @Override
    public Transition onKey(BbsContext ctx, String key) {
        if ("Q".equals(key)) {
            ctx.pop();
            return Transition.None.INSTANCE;
        }
        if (key.length() == 1 && Character.isDigit(key.charAt(0))) {
            int idx = Character.digit(key.charAt(0), 10);
            List<MessageBase> list = listFor(ctx, ctx.session().userId()).stream()
                    .map(BaseWithUnread::base)
                    .toList();
            if (idx >= 1 && idx <= list.size()) {
                ctx.session().setSelectedBaseId(list.get(idx - 1).id());
                ctx.push(Phase.THREADS_LIST);
            }
        }
        return Transition.None.INSTANCE;
    }

    private List<BaseWithUnread> listFor(BbsContext ctx, long userId) {
        return repo.listAllWithUnread(userId).stream()
                .filter(bu -> acl.can(ctx.session(), AclResourceType.MESSAGE_BASE,
                        bu.base().id(), AclPermission.VIEW))
                .toList();
    }
}
