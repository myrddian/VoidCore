package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.acl.AclPermission;
import io.aeyer.voidcore.acl.AclPrincipalType;
import io.aeyer.voidcore.acl.AclRepository;
import io.aeyer.voidcore.acl.AclResourceType;
import io.aeyer.voidcore.auth.RoleRepository;
import io.aeyer.voidcore.chat.ChatRepository;
import io.aeyer.voidcore.chat.ChatRoom;
import io.aeyer.voidcore.ws.VoidCoreSession;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.BbsServices;
import io.aeyer.voidcore.ws.flow.view.ChatView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SysopRoleChatRoomScreenTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    RoleRepository roles;
    ChatRepository chat;
    AclRepository acl;
    BbsContext ctx;
    BbsServices services;
    VoidCoreSession session;
    SysopRoleChatRoomScreen screen;

    @BeforeEach
    void setUp() {
        roles = mock(RoleRepository.class);
        chat = mock(ChatRepository.class);
        acl = mock(AclRepository.class);
        ctx = mock(BbsContext.class);
        services = mock(BbsServices.class);
        session = mock(VoidCoreSession.class);

        when(ctx.isSysop()).thenReturn(true);
        when(ctx.session()).thenReturn(session);
        when(ctx.services()).thenReturn(services);
        when(services.json()).thenReturn(JSON);
        when(session.selectedSysopId()).thenReturn(5L);
        when(session.selectedSysopSlug()).thenReturn("general");
        when(roles.findById(5L)).thenReturn(Optional.of(
                new RoleRepository.RoleRow(5L, "ADMIN_GAMES", "Games operator")));
        when(chat.findRoomBySlug("general")).thenReturn(Optional.of(
                new ChatRoom(1L, "general", "General", false, true, 1)));

        screen = new SysopRoleChatRoomScreen(roles, chat, acl);
    }

    @Test
    void V_grants_view_when_missing() {
        when(acl.hasGrant(AclResourceType.CHAT_ROOM, 1L, AclPermission.VIEW, AclPrincipalType.ROLE, 5L))
                .thenReturn(false);

        screen.onKey(ctx, "V");

        verify(acl).grant(AclResourceType.CHAT_ROOM, 1L, AclPermission.VIEW, AclPrincipalType.ROLE, 5L);
        verify(ctx).publish(ChatView.ROOMS_TOPIC);
        verify(ctx).publish(ChatView.topicFor("general"));
    }

    @Test
    void P_grants_post_and_view_when_missing() {
        when(acl.hasGrant(AclResourceType.CHAT_ROOM, 1L, AclPermission.POST, AclPrincipalType.ROLE, 5L))
                .thenReturn(false);

        screen.onKey(ctx, "P");

        verify(acl).grant(AclResourceType.CHAT_ROOM, 1L, AclPermission.VIEW, AclPrincipalType.ROLE, 5L);
        verify(acl).grant(AclResourceType.CHAT_ROOM, 1L, AclPermission.POST, AclPrincipalType.ROLE, 5L);
    }
}
