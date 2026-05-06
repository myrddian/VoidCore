package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.acl.AclPermission;
import io.aeyer.voidcore.acl.AclPrincipalType;
import io.aeyer.voidcore.acl.AclRepository;
import io.aeyer.voidcore.acl.AclResourceType;
import io.aeyer.voidcore.acl.AclService;
import io.aeyer.voidcore.auth.UserRepository;
import io.aeyer.voidcore.auth.UserRepository.UserRow;
import io.aeyer.voidcore.chat.ChatRepository;
import io.aeyer.voidcore.chat.ChatRoom;
import io.aeyer.voidcore.ws.VoidCoreSession;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.BbsServices;
import io.aeyer.voidcore.ws.flow.screen.MentionService;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.flow.view.ChatView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SysopChatRoomUserScreenTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    ChatRepository chat;
    UserRepository users;
    AclRepository acl;
    AclService aclService;
    BbsContext ctx;
    BbsServices services;
    MentionService mentions;
    VoidCoreSession session;
    UserRow user;
    SysopChatRoomUserScreen screen;

    @BeforeEach
    void setUp() {
        chat = mock(ChatRepository.class);
        users = mock(UserRepository.class);
        acl = mock(AclRepository.class);
        aclService = mock(AclService.class);
        ctx = mock(BbsContext.class);
        services = mock(BbsServices.class);
        mentions = mock(MentionService.class);
        session = mock(VoidCoreSession.class);
        user = mock(UserRow.class);

        when(ctx.isSysop()).thenReturn(true);
        when(ctx.session()).thenReturn(session);
        when(ctx.services()).thenReturn(services);
        when(services.json()).thenReturn(JSON);
        when(services.mentions()).thenReturn(mentions);
        when(session.selectedSysopSlug()).thenReturn("general");
        when(session.selectedSysopResourceId()).thenReturn(7L);
        when(chat.findRoomBySlug("general")).thenReturn(Optional.of(
                new ChatRoom(1L, "general", "General", true, false, true, 1)));
        when(users.findById(7L)).thenReturn(Optional.of(user));
        when(user.handle()).thenReturn("alice");
        when(user.isSysop()).thenReturn(false);

        screen = new SysopChatRoomUserScreen(chat, users, acl, aclService);
    }

    @Test
    void V_grants_direct_view_when_missing() {
        when(acl.hasGrant(AclResourceType.CHAT_ROOM, 1L, AclPermission.VIEW, AclPrincipalType.USER, 7L))
                .thenReturn(false);

        screen.onKey(ctx, "V");

        verify(acl).grant(AclResourceType.CHAT_ROOM, 1L, AclPermission.VIEW, AclPrincipalType.USER, 7L);
        verify(ctx).publish(ChatView.ROOMS_TOPIC);
        verify(ctx).publish(ChatView.topicFor("general"));
    }

    @Test
    void P_grants_direct_post_and_view_when_missing() {
        when(acl.hasGrant(AclResourceType.CHAT_ROOM, 1L, AclPermission.POST, AclPrincipalType.USER, 7L))
                .thenReturn(false);

        screen.onKey(ctx, "P");

        verify(acl).grant(AclResourceType.CHAT_ROOM, 1L, AclPermission.VIEW, AclPrincipalType.USER, 7L);
        verify(acl).grant(AclResourceType.CHAT_ROOM, 1L, AclPermission.POST, AclPrincipalType.USER, 7L);
    }

    @Test
    void K_revokes_direct_access_and_notifies_user() {
        screen.onKey(ctx, "K");

        verify(acl).revoke(AclResourceType.CHAT_ROOM, 1L, AclPermission.POST, AclPrincipalType.USER, 7L);
        verify(acl).revoke(AclResourceType.CHAT_ROOM, 1L, AclPermission.VIEW, AclPrincipalType.USER, 7L);
        verify(ctx).publish(ChatView.ROOMS_TOPIC);
        verify(ctx).publish(ChatView.topicFor("general"));
        verify(mentions).notifyUser(7L, Phase.CHAT_ROOM, "you were removed from #general", 4000);
    }
}
