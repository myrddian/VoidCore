package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.acl.AclPermission;
import io.aeyer.voidcore.acl.AclResourceType;
import io.aeyer.voidcore.acl.AclService;
import io.aeyer.voidcore.instance.InstanceFeature;
import io.aeyer.voidcore.messages.MessageBaseRepository;
import io.aeyer.voidcore.messages.MessageBaseRepository.BaseWithUnread;
import io.aeyer.voidcore.ws.flow.layout.Element;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.flow.screen.ScreenApp;
import io.aeyer.voidcore.ws.flow.screen.ScreenComponent;
import io.aeyer.voidcore.ws.flow.screen.Transition;
import io.aeyer.voidcore.ws.flow.ui.AppEvent;
import io.aeyer.voidcore.ws.protocol.ServerMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * Forum: list of message-board areas.
 *
 * <p>First screen on the {@code list} element (ADR-035). It used to paint
 * hand-built rows with a number against each board; now it composes a
 * {@link Element.ListView} and the client owns the highlight.
 *
 * <p><strong>Both selection styles work.</strong> Arrow keys and
 * {@code j}/{@code k} move the highlight client-side and cost no round
 * trip; {@code Enter} commits. The BBS-native {@code [1-9]} still enters a
 * board directly, because the list widget only consumes the keys it
 * handles — anything else bubbles to the client's input layer and arrives
 * here as a keystroke. Removing single-key selection would have been a
 * regression for anyone who navigates a board by touch-typing digits.
 *
 * <p>Bases data is global but rarely-changing; per-user unread counts are
 * joined at query time, so a direct {@code repo.listAllWithUnread(uid)}
 * per paint matches the {@code netmail-inbox} pattern.
 */
@ScreenComponent
public class BasesListScreen extends ScreenApp {

    public static final String TOPIC = "message_bases";

    /** Widget id, echoed back on {@code list.selected}. */
    private static final String LIST_ID = "bases";

    private final MessageBaseRepository repo;
    private final AclService acl;

    public BasesListScreen(MessageBaseRepository repo, AclService acl) {
        this.repo = repo;
        this.acl = acl;
    }

    @Override public Phase phase() { return Phase.BASES_LIST; }
    @Override public String name() { return "bases-list"; }
    @Override protected String appKey(BbsContext ctx) { return "bases-list"; }

    @Override
    public List<String> topics(BbsContext ctx) {
        return ScreenFeatureGate.withTopic(List.of(TOPIC));
    }

    @Override
    public Transition onEnter(BbsContext ctx) {
        if (!ScreenFeatureGate.ensureEnabled(ctx, InstanceFeature.MESSAGE_BOARD, "message board")) {
            return Transition.None.INSTANCE;
        }
        if (ctx.session().userId() == null) {
            ctx.pop();
            return Transition.None.INSTANCE;
        }
        ctx.persistCurrentScreen("{\"kind\":\"bases\"}");
        return super.onEnter(ctx);
    }

    @Override
    protected Element compose(BbsContext ctx) {
        List<BaseWithUnread> list = listFor(ctx);

        List<Element> children = new ArrayList<>();
        children.add(new Element.Header("MESSAGE BOARD", list.isEmpty() ? null
                : list.size() + (list.size() == 1 ? " board" : " boards")));
        children.add(new Element.Spacer(1));

        if (list.isEmpty()) {
            children.add(new Element.Text("  (no boards)", "dark_grey"));
        } else {
            children.add(new Element.ListView(LIST_ID, items(list), selectedId(ctx, list)));
        }

        children.add(new Element.Spacer(1));
        children.add(new Element.KeyMenu(List.of(
                new Element.KeyMenu.KeyEntry("1-9", "enter board"),
                new Element.KeyMenu.KeyEntry("↑↓", "move"),
                new Element.KeyMenu.KeyEntry("Enter", "open"),
                new Element.KeyMenu.KeyEntry("Q", "back to menu"))));

        return new Element.VStack(children, 0);
    }

    private List<Element.ListView.Item> items(List<BaseWithUnread> list) {
        List<Element.ListView.Item> items = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            BaseWithUnread bu = list.get(i);
            // Keep the digit visible: it is still a live shortcut, and the
            // numbering is what a returning user's fingers already know.
            String prefix = i < 9 ? "[" + (i + 1) + "] " : "    ";
            items.add(new Element.ListView.Item(
                    String.valueOf(bu.base().id()),
                    prefix + bu.base().slug() + "  " + bu.base().name(),
                    bu.unread() == 0 ? null : bu.unread() + " unread"));
        }
        return items;
    }

    /** Re-highlight the board the user last visited, when they come back. */
    private String selectedId(BbsContext ctx, List<BaseWithUnread> list) {
        Long selected = ctx.session().selectedBaseId();
        if (selected == null) return null;
        return list.stream()
                .anyMatch(bu -> bu.base().id() == selected) ? String.valueOf(selected) : null;
    }

    @Override
    protected void onEvent(BbsContext ctx, AppEvent ev) {
        switch (ev) {
            case AppEvent.ListSelected ls -> {
                if (LIST_ID.equals(ls.widgetId())) open(ctx, ls.itemId());
            }
            case AppEvent.FieldCancel fc -> popAndExit(ctx);
            default -> { /* no other widgets on this screen */ }
        }
    }

    /**
     * Keystroke path, still live alongside the list. {@code [Q]} leaves;
     * a digit enters that board directly.
     */
    @Override
    public Transition onKey(BbsContext ctx, String key) {
        if ("Q".equals(key)) {
            popAndExit(ctx);
            return Transition.None.INSTANCE;
        }
        if (key.length() == 1 && Character.isDigit(key.charAt(0))) {
            int idx = Character.digit(key.charAt(0), 10);
            List<BaseWithUnread> list = listFor(ctx);
            if (idx >= 1 && idx <= list.size()) {
                enter(ctx, list.get(idx - 1).base().id());
            }
        }
        return Transition.None.INSTANCE;
    }

    @Override
    public Transition onCancel(BbsContext ctx) {
        popAndExit(ctx);
        return Transition.None.INSTANCE;
    }

    /** Selection arrives as an item id — a board id as a string. */
    private void open(BbsContext ctx, String itemId) {
        long id;
        try {
            id = Long.parseLong(itemId);
        } catch (NumberFormatException e) {
            return;
        }
        // Re-check visibility rather than trusting the id off the wire: the
        // client could name a board this user may not read.
        if (listFor(ctx).stream().noneMatch(bu -> bu.base().id() == id)) return;
        enter(ctx, id);
    }

    private void enter(BbsContext ctx, long baseId) {
        ctx.session().setSelectedBaseId(baseId);
        ctx.push(Phase.THREADS_LIST);
    }

    /**
     * Keystroke prompt with the live digits, so single-key selection keeps
     * reaching the server. Keys the list widget doesn't consume bubble to
     * the client's input layer and arrive here.
     */
    @Override
    protected ServerMessage.InputPrompt defaultInputPrompt(BbsContext ctx) {
        StringBuilder valid = new StringBuilder("Q");
        int boards = Math.min(9, listFor(ctx).size());
        for (int i = 1; i <= boards; i++) valid.append(i);
        return new ServerMessage.InputPrompt("keystroke", "board:", null, valid.toString(), null);
    }

    private List<BaseWithUnread> listFor(BbsContext ctx) {
        Long uid = ctx.session().userId();
        if (uid == null) return List.of();
        return repo.listAllWithUnread(uid).stream()
                .filter(bu -> acl.can(ctx.session(), AclResourceType.MESSAGE_BASE,
                        bu.base().id(), AclPermission.VIEW))
                .toList();
    }
}
