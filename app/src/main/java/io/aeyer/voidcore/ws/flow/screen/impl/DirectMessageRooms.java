package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.acl.AclPermission;
import io.aeyer.voidcore.acl.AclPrincipalType;
import io.aeyer.voidcore.acl.AclResourceType;
import io.aeyer.voidcore.acl.AclService;
import io.aeyer.voidcore.auth.UserRepository.UserRow;
import io.aeyer.voidcore.chat.ChatRepository;
import io.aeyer.voidcore.chat.ChatRoom;

import java.util.List;
import java.util.Locale;

final class DirectMessageRooms {

    private static final String DM_SUFFIX = "-dm";

    private DirectMessageRooms() {}

    static ChatRoom ensure(ChatRepository repo,
                           AclService acl,
                           long senderUserId,
                           String senderHandle,
                           UserRow target) {
        String slug = slugFor(senderHandle, target.handle());
        ChatRoom existing = repo.findRoomBySlug(slug).orElse(null);
        long roomId;
        if (existing == null) {
            roomId = repo.createRoom(slug, labelFor(senderHandle, target.handle()), true, true);
        } else {
            roomId = existing.id();
            if (!existing.active()) repo.setRoomActive(roomId, true);
        }
        repo.addRoomMember(roomId, senderUserId);
        repo.addRoomMember(roomId, target.id());
        acl.grant(AclResourceType.CHAT_ROOM, roomId, AclPermission.VIEW, AclPrincipalType.USER, senderUserId);
        acl.grant(AclResourceType.CHAT_ROOM, roomId, AclPermission.POST, AclPrincipalType.USER, senderUserId);
        acl.grant(AclResourceType.CHAT_ROOM, roomId, AclPermission.VIEW, AclPrincipalType.USER, target.id());
        acl.grant(AclResourceType.CHAT_ROOM, roomId, AclPermission.POST, AclPrincipalType.USER, target.id());
        return repo.findActiveRoomBySlug(slug)
                .orElseThrow(() -> new IllegalStateException("direct room was not available after create: " + slug));
    }

    static String slugFor(String leftHandle, String rightHandle) {
        List<String> handles = List.of(
                leftHandle.toLowerCase(Locale.ROOT),
                rightHandle.toLowerCase(Locale.ROOT)).stream().sorted().toList();
        return handles.get(0) + "-" + handles.get(1) + DM_SUFFIX;
    }

    static String labelFor(String leftHandle, String rightHandle) {
        List<String> handles = List.of(leftHandle, rightHandle).stream()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
        return "DM · " + handles.get(0) + " / " + handles.get(1);
    }
}
