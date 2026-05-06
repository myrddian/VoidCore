package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.acl.AclPermission;
import io.aeyer.voidcore.acl.AclPrincipalType;
import io.aeyer.voidcore.acl.AclRepository;
import io.aeyer.voidcore.acl.AclResourceType;
import io.aeyer.voidcore.auth.RoleRepository;
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
public class SysopRoleChatRoomScreen implements Screen {

    private final RoleRepository roles;
    private final ChatRepository chat;
    private final AclRepository acl;

    public SysopRoleChatRoomScreen(RoleRepository roles, ChatRepository chat, AclRepository acl) {
        this.roles = roles;
        this.chat = chat;
        this.acl = acl;
    }

    @Override public Phase phase() { return Phase.SYSOP_ROLE_CHAT_ROOM; }
    @Override public String name() { return "sysop-role-chat-room"; }

    @Override
    public Transition onEnter(BbsContext ctx) {
        if (!ctx.isSysop()) { ctx.pop(); return Transition.None.INSTANCE; }
        Long roleId = ctx.session().selectedSysopId();
        String roomSlug = ctx.session().selectedSysopSlug();
        if (roleId == null || roomSlug == null) { ctx.pop(); return Transition.None.INSTANCE; }
        var role = roles.findById(roleId).orElse(null);
        ChatRoom room = chat.findRoomBySlug(roomSlug).orElse(null);
        if (role == null || room == null) { ctx.pop(); return Transition.None.INSTANCE; }

        boolean canView = acl.hasGrant(AclResourceType.CHAT_ROOM, room.id(), AclPermission.VIEW, AclPrincipalType.ROLE, roleId);
        boolean canPost = acl.hasGrant(AclResourceType.CHAT_ROOM, room.id(), AclPermission.POST, AclPrincipalType.ROLE, roleId);

        ctx.send(Frames.update("main", 78, Frames.textRows(java.util.List.of(
                "",
                "  == ROLE CHAT GRANT ==",
                "",
                "  role : " + role.name(),
                "  room : #" + room.slug(),
                "",
                "  [V] view permission : " + (canView ? "ON" : "OFF"),
                "  [P] post permission : " + (canPost ? "ON" : "OFF"),
                "",
                "  [Q] back"
        ), "default")));
        ctx.send(new InputPrompt("keystroke", "grant:", null, "VPQ", null));
        return Transition.None.INSTANCE;
    }

    @Override
    public Transition onKey(BbsContext ctx, String key) {
        if (!ctx.isSysop()) return Transition.None.INSTANCE;
        if ("Q".equals(key)) { ctx.pop(); return Transition.None.INSTANCE; }
        Long roleId = ctx.session().selectedSysopId();
        String roomSlug = ctx.session().selectedSysopSlug();
        if (roleId == null || roomSlug == null) return Transition.None.INSTANCE;
        ChatRoom room = chat.findRoomBySlug(roomSlug).orElse(null);
        if (room == null) return Transition.None.INSTANCE;

        if ("V".equals(key)) {
            boolean has = acl.hasGrant(AclResourceType.CHAT_ROOM, room.id(), AclPermission.VIEW, AclPrincipalType.ROLE, roleId);
            if (has) {
                acl.revoke(AclResourceType.CHAT_ROOM, room.id(), AclPermission.VIEW, AclPrincipalType.ROLE, roleId);
                acl.revoke(AclResourceType.CHAT_ROOM, room.id(), AclPermission.POST, AclPrincipalType.ROLE, roleId);
            } else {
                acl.grant(AclResourceType.CHAT_ROOM, room.id(), AclPermission.VIEW, AclPrincipalType.ROLE, roleId);
            }
            publishRoomRefresh(ctx, room, roleId, has ? "revoked view from role" : "granted view to role");
        } else if ("P".equals(key)) {
            boolean hasPost = acl.hasGrant(AclResourceType.CHAT_ROOM, room.id(), AclPermission.POST, AclPrincipalType.ROLE, roleId);
            if (hasPost) {
                acl.revoke(AclResourceType.CHAT_ROOM, room.id(), AclPermission.POST, AclPrincipalType.ROLE, roleId);
            } else {
                acl.grant(AclResourceType.CHAT_ROOM, room.id(), AclPermission.VIEW, AclPrincipalType.ROLE, roleId);
                acl.grant(AclResourceType.CHAT_ROOM, room.id(), AclPermission.POST, AclPrincipalType.ROLE, roleId);
            }
            publishRoomRefresh(ctx, room, roleId, hasPost ? "revoked post from role" : "granted post to role");
        }
        onEnter(ctx);
        return Transition.None.INSTANCE;
    }

    private void publishRoomRefresh(BbsContext ctx, ChatRoom room, long roleId, String note) {
        ctx.publish(ChatView.ROOMS_TOPIC);
        ctx.publish(ChatView.topicFor(room.slug()));
        ctx.audit("update_role_room_acl",
                ctx.services().json().createObjectNode()
                        .put("role_id", roleId)
                        .put("room_id", room.id())
                        .put("slug", room.slug())
                        .put("note", note));
        ctx.send(Frames.notify("notifications", note, "info", 2500));
    }
}
