package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.acl.AclPermission;
import io.aeyer.voidcore.acl.AclPrincipalType;
import io.aeyer.voidcore.acl.AclRepository;
import io.aeyer.voidcore.auth.UserRepository;
import io.aeyer.voidcore.chat.ChatRepository;
import io.aeyer.voidcore.chat.ChatRoom;
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

@ScreenComponent
public class SysopUserChatRoomsScreen implements Screen {

    private final UserRepository users;
    private final ChatRepository chat;
    private final AclRepository acl;

    public SysopUserChatRoomsScreen(UserRepository users,
                                    ChatRepository chat,
                                    AclRepository acl) {
        this.users = users;
        this.chat = chat;
        this.acl = acl;
    }

    @Override public Phase phase() { return Phase.SYSOP_USER_CHAT_ROOMS; }
    @Override public String name() { return "sysop-user-chat-rooms"; }

    @Override
    public Transition onEnter(BbsContext ctx) {
        if (!ctx.isSysop()) { ctx.pop(); return Transition.None.INSTANCE; }
        Long userId = ctx.session().selectedSysopId();
        if (userId == null) { ctx.pop(); return Transition.None.INSTANCE; }
        var user = users.findById(userId).orElse(null);
        if (user == null) { ctx.pop(); return Transition.None.INSTANCE; }
        ctx.session().setSelectedSysopSlug(null);

        List<ChatRoom> rooms = chat.listAllRooms();
        ArrayList<Row> rows = new ArrayList<>();
        rows.add(Frames.colored(0,
                "  == USER CHAT ACCESS · " + user.handle().toUpperCase() + " ==",
                "bright_yellow"));
        rows.add(Frames.blank(1));
        rows.add(Frames.colored(2,
                "      room               direct view   direct post   status",
                "dark_grey"));
        int rowN = 3;
        for (int i = 0; i < rooms.size(); i++) {
            ChatRoom room = rooms.get(i);
            boolean directView = acl.hasGrant(
                    io.aeyer.voidcore.acl.AclResourceType.CHAT_ROOM,
                    room.id(),
                    AclPermission.VIEW,
                    AclPrincipalType.USER,
                    userId);
            boolean directPost = acl.hasGrant(
                    io.aeyer.voidcore.acl.AclResourceType.CHAT_ROOM,
                    room.id(),
                    AclPermission.POST,
                    AclPrincipalType.USER,
                    userId);
            rows.add(Frames.row(rowN++,
                    Frames.span("  [", "grey"),
                    Frames.span(String.valueOf(i + 1), "bright_yellow", true),
                    Frames.span("] ", "grey"),
                    Frames.span(ScreenText.padRight("#" + room.slug(), 18), "bright_cyan", true),
                    Frames.span(ScreenText.padRight(directView ? "ON" : "off", 12),
                            directView ? "bright_green" : "dark_grey"),
                    Frames.span(ScreenText.padRight(directPost ? "ON" : "off", 12),
                            directPost ? "bright_green" : "dark_grey"),
                    Frames.span(room.active() ? "active" : "disabled",
                            room.active() ? "default" : "bright_red")));
        }
        rows.add(Frames.blank(rowN++));
        rows.add(Frames.colored(rowN++,
                "  direct grants only — role-based access still applies separately",
                "dark_grey"));
        rows.add(Frames.row(rowN,
                Frames.span("  Pick room number to manage direct access   [", "grey"),
                Frames.span("Q", "bright_yellow", true),
                Frames.span("] back", "grey")));
        ctx.send(Frames.update("main", 78, rows));

        StringBuilder valid = new StringBuilder("Q");
        for (int i = 0; i < rooms.size(); i++) valid.append(i + 1);
        ctx.send(new InputPrompt("keystroke", "user room:", null, valid.toString(), null));
        return Transition.None.INSTANCE;
    }

    @Override
    public Transition onKey(BbsContext ctx, String key) {
        if (!ctx.isSysop()) return Transition.None.INSTANCE;
        if ("Q".equals(key)) { ctx.pop(); return Transition.None.INSTANCE; }
        if (key.length() == 1 && Character.isDigit(key.charAt(0))) {
            int idx = Character.digit(key.charAt(0), 10);
            List<ChatRoom> rooms = chat.listAllRooms();
            if (idx >= 1 && idx <= rooms.size()) {
                ctx.session().setSelectedSysopSlug(rooms.get(idx - 1).slug());
                ctx.push(Phase.SYSOP_USER_CHAT_ROOM);
            }
        }
        return Transition.None.INSTANCE;
    }
}
