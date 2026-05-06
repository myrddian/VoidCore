package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.acl.AclPermission;
import io.aeyer.voidcore.acl.AclPrincipalType;
import io.aeyer.voidcore.acl.AclRepository;
import io.aeyer.voidcore.acl.AclResourceType;
import io.aeyer.voidcore.acl.AclService;
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
public class SysopChatRoomUsersScreen implements Screen {

    private final ChatRepository chat;
    private final UserRepository users;
    private final AclRepository acl;
    private final AclService aclService;

    public SysopChatRoomUsersScreen(ChatRepository chat,
                                    UserRepository users,
                                    AclRepository acl,
                                    AclService aclService) {
        this.chat = chat;
        this.users = users;
        this.acl = acl;
        this.aclService = aclService;
    }

    @Override public Phase phase() { return Phase.SYSOP_CHAT_ROOM_USERS; }
    @Override public String name() { return "sysop-chat-room-users"; }

    @Override
    public Transition onEnter(BbsContext ctx) {
        if (!ctx.isSysop()) { ctx.pop(); return Transition.None.INSTANCE; }
        String roomSlug = ctx.session().selectedSysopSlug();
        if (roomSlug == null) { ctx.pop(); return Transition.None.INSTANCE; }
        ChatRoom room = chat.findRoomBySlug(roomSlug).orElse(null);
        if (room == null) { ctx.pop(); return Transition.None.INSTANCE; }
        ctx.session().setSelectedSysopResourceId(null);

        List<UserRepository.UserSummary> list = users.listForBoard(9);
        ArrayList<Row> rows = new ArrayList<>();
        rows.add(Frames.colored(0,
                "  == ROOM USER ACCESS · #" + room.slug().toUpperCase() + " ==",
                "bright_yellow"));
        rows.add(Frames.blank(1));
        rows.add(Frames.colored(2,
                "      user             direct view post   effective",
                "dark_grey"));
        int rowN = 3;
        for (int i = 0; i < list.size(); i++) {
            var user = list.get(i);
            boolean directView = acl.hasGrant(AclResourceType.CHAT_ROOM, room.id(),
                    AclPermission.VIEW, AclPrincipalType.USER, user.id());
            boolean directPost = acl.hasGrant(AclResourceType.CHAT_ROOM, room.id(),
                    AclPermission.POST, AclPrincipalType.USER, user.id());
            boolean effectiveView = aclService.canUser(user.id(), user.isSysop(),
                    AclResourceType.CHAT_ROOM, room.id(), AclPermission.VIEW);
            boolean effectivePost = aclService.canUser(user.id(), user.isSysop(),
                    AclResourceType.CHAT_ROOM, room.id(), AclPermission.POST);
            rows.add(Frames.row(rowN++,
                    Frames.span("  [", "grey"),
                    Frames.span(String.valueOf(i + 1), "bright_yellow", true),
                    Frames.span("] ", "grey"),
                    Frames.span(ScreenText.padRight(user.handle(), 16), "bright_cyan", true),
                    Frames.span(directView ? "ON   " : "off  ", directView ? "bright_green" : "dark_grey"),
                    Frames.span(directPost ? "ON   " : "off  ", directPost ? "bright_green" : "dark_grey"),
                    Frames.span((effectiveView ? "view" : "-") + "/" + (effectivePost ? "post" : "-"),
                            (effectiveView || effectivePost) ? "default" : "dark_grey")));
        }
        rows.add(Frames.blank(rowN++));
        rows.add(Frames.colored(rowN++,
                "  top 9 non-banned users; direct grants stack with roles",
                "dark_grey"));
        rows.add(Frames.row(rowN,
                Frames.span("  Pick user number to manage direct grants   [", "grey"),
                Frames.span("Q", "bright_yellow", true),
                Frames.span("] back", "grey")));
        ctx.send(Frames.update("main", 78, rows));

        StringBuilder valid = new StringBuilder("Q");
        for (int i = 0; i < list.size(); i++) valid.append(i + 1);
        ctx.send(new InputPrompt("keystroke", "room user:", null, valid.toString(), null));
        return Transition.None.INSTANCE;
    }

    @Override
    public Transition onKey(BbsContext ctx, String key) {
        if (!ctx.isSysop()) return Transition.None.INSTANCE;
        if ("Q".equals(key)) { ctx.pop(); return Transition.None.INSTANCE; }
        if (key.length() == 1 && Character.isDigit(key.charAt(0))) {
            int idx = Character.digit(key.charAt(0), 10);
            var list = users.listForBoard(9);
            if (idx >= 1 && idx <= list.size()) {
                ctx.session().setSelectedSysopResourceId(list.get(idx - 1).id());
                ctx.push(Phase.SYSOP_CHAT_ROOM_USER);
            }
        }
        return Transition.None.INSTANCE;
    }
}
