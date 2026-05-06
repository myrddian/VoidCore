package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.acl.AclPermission;
import io.aeyer.voidcore.acl.AclResourceType;
import io.aeyer.voidcore.acl.AclService;
import io.aeyer.voidcore.chat.ChatRepository;
import io.aeyer.voidcore.chat.ChatRoom;
import io.aeyer.voidcore.ws.flow.Frames;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.flow.screen.Screen;
import io.aeyer.voidcore.ws.flow.screen.ScreenComponent;
import io.aeyer.voidcore.ws.flow.screen.ScreenText;
import io.aeyer.voidcore.ws.flow.screen.Transition;
import io.aeyer.voidcore.ws.flow.view.ChatView;
import io.aeyer.voidcore.ws.protocol.ServerMessage.InputPrompt;
import io.aeyer.voidcore.ws.protocol.ServerMessage.Row;

import java.util.ArrayList;
import java.util.List;

@ScreenComponent
public class SysopChatRoomsScreen implements Screen {

    private static final long PEND_TOGGLE = -11L;
    private static final long PEND_USER_ACCESS = -12L;

    private final ChatRepository repo;
    private final AclService acl;

    public SysopChatRoomsScreen(ChatRepository repo, AclService acl) {
        this.repo = repo;
        this.acl = acl;
    }

    @Override public Phase phase() { return Phase.SYSOP_CHAT_ROOMS; }
    @Override public String name() { return "sysop-chat-rooms"; }

    @Override
    public List<String> topics(BbsContext ctx) {
        return List.of(ChatView.ROOMS_TOPIC);
    }

    @Override
    public Transition onEnter(BbsContext ctx) {
        if (!canEnter(ctx)) { ctx.pop(); return Transition.None.INSTANCE; }
        ctx.persistCurrentScreen("{\"kind\":\"sysop_chat_rooms\"}");
        List<ChatRoom> rooms = rooms(ctx);

        ArrayList<Row> rows = new ArrayList<>();
        rows.add(Frames.colored(0, "  == SYSOP · CHAT ROOMS ==   " + rooms.size(), "bright_yellow"));
        rows.add(Frames.blank(1));
        int rowN = 2;
        if (rooms.isEmpty()) {
            rows.add(Frames.colored(rowN++, "  (no chat rooms yet)", "dark_grey"));
        } else {
            for (int i = 0; i < rooms.size(); i++) {
                ChatRoom room = rooms.get(i);
                rows.add(Frames.row(rowN++,
                        Frames.span("  [", "grey"),
                        Frames.span(String.valueOf(i + 1), "bright_yellow", true),
                        Frames.span("] ", "grey"),
                        Frames.span(room.active() ? "[on]  " : "[off] ", room.active() ? "bright_green" : "bright_red"),
                        Frames.span(ScreenText.padRight("#" + room.slug(), 16), "bright_cyan", true),
                        Frames.span(ScreenText.padRight(room.label(), 28), "default"),
                        Frames.span(room.privateRoom() ? "private" : "public", "dark_grey")));
            }
        }
        rows.add(Frames.blank(rowN++));
        rows.add(Frames.row(rowN,
                Frames.span("  [", "grey"),
                Frames.span(canCreate(ctx) ? "N" : "-", canCreate(ctx) ? "bright_yellow" : "dark_grey", canCreate(ctx)),
                Frames.span("] new room   [", "grey"),
                Frames.span("U", "bright_yellow", true),
                Frames.span("] user access   [", "grey"),
                Frames.span("T", "bright_yellow", true),
                Frames.span("] disable / re-enable   [", "grey"),
                Frames.span("Q", "bright_yellow", true),
                Frames.span("] back", "grey")));
        ctx.send(Frames.update("main", 74, rows));

        StringBuilder valid = new StringBuilder("UTQ");
        if (canCreate(ctx)) valid.append('N');
        for (int i = 0; i < rooms.size(); i++) valid.append(i + 1);
        ctx.send(new InputPrompt("keystroke", "chat room:", null, valid.toString(), null));
        return Transition.None.INSTANCE;
    }

    @Override
    public Transition onKey(BbsContext ctx, String key) {
        if (!canEnter(ctx)) return Transition.None.INSTANCE;
        switch (key) {
            case "Q" -> {
                ctx.session().setSelectedSysopId(null);
                ctx.pop();
            }
            case "N" -> {
                if (!canCreate(ctx)) return Transition.None.INSTANCE;
                ctx.session().setSelectedSysopId(null);
                ctx.push(Phase.SYSOP_CHAT_ROOM_NEW);
            }
            case "T" -> {
                ctx.send(Frames.notify("notifications",
                        "pick the room number to disable or re-enable", "info", 2500));
                ctx.session().setSelectedSysopId(PEND_TOGGLE);
            }
            case "U" -> {
                ctx.send(Frames.notify("notifications",
                        "pick the room number to manage user access", "info", 2500));
                ctx.session().setSelectedSysopId(PEND_USER_ACCESS);
            }
            default -> {
                if (key.length() == 1 && Character.isDigit(key.charAt(0))) {
                    int idx = Character.digit(key.charAt(0), 10);
                    List<ChatRoom> rooms = rooms(ctx);
                    if (idx < 1 || idx > rooms.size()) return Transition.None.INSTANCE;
                    ChatRoom room = rooms.get(idx - 1);
                    Long pending = ctx.session().selectedSysopId();
                    if (pending != null && pending == PEND_TOGGLE) {
                        boolean enable = !room.active();
                        repo.setRoomActive(room.id(), enable);
                        ctx.publish(ChatView.ROOMS_TOPIC);
                        ctx.publish(ChatView.topicFor(room.slug()));
                        ctx.audit(enable ? "enable_chat_room" : "disable_chat_room",
                                ctx.services().json().createObjectNode()
                                        .put("room_id", room.id())
                                        .put("slug", room.slug())
                                        .put("label", room.label()));
                        ctx.send(Frames.notify("notifications",
                                (enable ? "re-enabled: " : "disabled: ") + "#" + room.slug(),
                                enable ? "info" : "warn", 3000));
                        ctx.session().setSelectedSysopId(null);
                        onEnter(ctx);
                    } else if (pending != null && pending == PEND_USER_ACCESS) {
                        ctx.session().setSelectedSysopId(null);
                        ctx.session().setSelectedSysopSlug(room.slug());
                        ctx.push(Phase.SYSOP_CHAT_ROOM_USERS);
                    }
                }
            }
        }
        return Transition.None.INSTANCE;
    }

    private boolean canEnter(BbsContext ctx) {
        return ctx.isSysop() || rooms(ctx).stream().findAny().isPresent();
    }

    private boolean canCreate(BbsContext ctx) {
        return canEnter(ctx);
    }

    private List<ChatRoom> rooms(BbsContext ctx) {
        return repo.listAllRooms().stream()
                .filter(room -> ctx.isSysop()
                        || acl.can(ctx.session(), AclResourceType.CHAT_ROOM, room.id(), AclPermission.MANAGE))
                .toList();
    }
}
