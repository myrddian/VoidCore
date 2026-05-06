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
import io.aeyer.voidcore.ws.flow.screen.Transition;
import io.aeyer.voidcore.ws.flow.view.ChatView;
import io.aeyer.voidcore.ws.protocol.ServerMessage.InputPrompt;

@ScreenComponent
public class SysopUserChatRoomScreen implements Screen {

    private final UserRepository users;
    private final ChatRepository chat;
    private final AclRepository acl;
    private final AclService aclService;

    public SysopUserChatRoomScreen(UserRepository users,
                                   ChatRepository chat,
                                   AclRepository acl,
                                   AclService aclService) {
        this.users = users;
        this.chat = chat;
        this.acl = acl;
        this.aclService = aclService;
    }

    @Override public Phase phase() { return Phase.SYSOP_USER_CHAT_ROOM; }
    @Override public String name() { return "sysop-user-chat-room"; }

    @Override
    public Transition onEnter(BbsContext ctx) {
        if (!ctx.isSysop()) { ctx.pop(); return Transition.None.INSTANCE; }
        Long userId = ctx.session().selectedSysopId();
        String roomSlug = ctx.session().selectedSysopSlug();
        if (userId == null || roomSlug == null) { ctx.pop(); return Transition.None.INSTANCE; }
        var user = users.findById(userId).orElse(null);
        ChatRoom room = chat.findRoomBySlug(roomSlug).orElse(null);
        if (user == null || room == null) { ctx.pop(); return Transition.None.INSTANCE; }

        boolean directView = acl.hasGrant(AclResourceType.CHAT_ROOM, room.id(),
                AclPermission.VIEW, AclPrincipalType.USER, userId);
        boolean directPost = acl.hasGrant(AclResourceType.CHAT_ROOM, room.id(),
                AclPermission.POST, AclPrincipalType.USER, userId);
        boolean effectiveView = aclService.canUser(userId, user.isSysop(),
                AclResourceType.CHAT_ROOM, room.id(), AclPermission.VIEW);
        boolean effectivePost = aclService.canUser(userId, user.isSysop(),
                AclResourceType.CHAT_ROOM, room.id(), AclPermission.POST);

        ctx.send(Frames.update("main", 78, Frames.textRows(java.util.List.of(
                "",
                "  == USER CHAT GRANT ==",
                "",
                "  user : " + user.handle(),
                "  room : #" + room.slug(),
                "",
                "  [V] direct view grant : " + (directView ? "ON" : "OFF"),
                "  [P] direct post grant : " + (directPost ? "ON" : "OFF"),
                "",
                "  effective view : " + (effectiveView ? "YES" : "NO"),
                "  effective post : " + (effectivePost ? "YES" : "NO"),
                "",
                "  [K] kick from room",
                "",
                "  direct grants stack with roles; turning a direct grant OFF",
                "  does not remove access inherited from the user's roles.",
                "",
                "  [Q] back"
        ), "default")));
        ctx.send(new InputPrompt("keystroke", "grant:", null, "VPKQ", null));
        return Transition.None.INSTANCE;
    }

    @Override
    public Transition onKey(BbsContext ctx, String key) {
        if (!ctx.isSysop()) return Transition.None.INSTANCE;
        if ("Q".equals(key)) { ctx.pop(); return Transition.None.INSTANCE; }
        Long userId = ctx.session().selectedSysopId();
        String roomSlug = ctx.session().selectedSysopSlug();
        if (userId == null || roomSlug == null) return Transition.None.INSTANCE;
        ChatRoom room = chat.findRoomBySlug(roomSlug).orElse(null);
        if (room == null) return Transition.None.INSTANCE;

        if ("V".equals(key)) {
            boolean has = acl.hasGrant(AclResourceType.CHAT_ROOM, room.id(),
                    AclPermission.VIEW, AclPrincipalType.USER, userId);
            if (has) {
                acl.revoke(AclResourceType.CHAT_ROOM, room.id(), AclPermission.VIEW, AclPrincipalType.USER, userId);
                acl.revoke(AclResourceType.CHAT_ROOM, room.id(), AclPermission.POST, AclPrincipalType.USER, userId);
            } else {
                acl.grant(AclResourceType.CHAT_ROOM, room.id(), AclPermission.VIEW, AclPrincipalType.USER, userId);
            }
            publishRoomRefresh(ctx, room, userId, has ? "revoked direct room view" : "granted direct room view");
        } else if ("P".equals(key)) {
            boolean hasPost = acl.hasGrant(AclResourceType.CHAT_ROOM, room.id(),
                    AclPermission.POST, AclPrincipalType.USER, userId);
            if (hasPost) {
                acl.revoke(AclResourceType.CHAT_ROOM, room.id(), AclPermission.POST, AclPrincipalType.USER, userId);
            } else {
                acl.grant(AclResourceType.CHAT_ROOM, room.id(), AclPermission.VIEW, AclPrincipalType.USER, userId);
                acl.grant(AclResourceType.CHAT_ROOM, room.id(), AclPermission.POST, AclPrincipalType.USER, userId);
            }
            publishRoomRefresh(ctx, room, userId, hasPost ? "revoked direct room post" : "granted direct room post");
        } else if ("K".equals(key)) {
            acl.revoke(AclResourceType.CHAT_ROOM, room.id(), AclPermission.POST, AclPrincipalType.USER, userId);
            acl.revoke(AclResourceType.CHAT_ROOM, room.id(), AclPermission.VIEW, AclPrincipalType.USER, userId);
            publishKick(ctx, room, userId);
        }
        onEnter(ctx);
        return Transition.None.INSTANCE;
    }

    private void publishRoomRefresh(BbsContext ctx, ChatRoom room, long userId, String note) {
        ctx.publish(ChatView.ROOMS_TOPIC);
        ctx.publish(ChatView.topicFor(room.slug()));
        ctx.audit("update_user_room_acl",
                ctx.services().json().createObjectNode()
                        .put("user_id", userId)
                        .put("room_id", room.id())
                        .put("slug", room.slug())
                        .put("note", note));
        ctx.send(Frames.notify("notifications", note, "info", 2500));
    }

    private void publishKick(BbsContext ctx, ChatRoom room, long userId) {
        ctx.publish(ChatView.ROOMS_TOPIC);
        ctx.publish(ChatView.topicFor(room.slug()));
        ctx.services().mentions().notifyUser(userId, Phase.CHAT_ROOM,
                "you were removed from #" + room.slug(), 4000);
        ctx.audit("kick_user_from_room",
                ctx.services().json().createObjectNode()
                        .put("user_id", userId)
                        .put("room_id", room.id())
                        .put("slug", room.slug()));
        ctx.send(Frames.notify("notifications",
                "removed user from #" + room.slug(), "info", 2500));
    }
}
