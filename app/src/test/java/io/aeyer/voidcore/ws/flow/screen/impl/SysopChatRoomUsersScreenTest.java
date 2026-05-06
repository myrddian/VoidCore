package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.acl.AclRepository;
import io.aeyer.voidcore.acl.AclService;
import io.aeyer.voidcore.auth.UserRepository;
import io.aeyer.voidcore.chat.ChatRepository;
import io.aeyer.voidcore.chat.ChatRoom;
import io.aeyer.voidcore.ws.VoidCoreSession;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SysopChatRoomUsersScreenTest {

    ChatRepository chat;
    UserRepository users;
    AclRepository acl;
    AclService aclService;
    BbsContext ctx;
    VoidCoreSession session;
    SysopChatRoomUsersScreen screen;

    @BeforeEach
    void setUp() {
        chat = mock(ChatRepository.class);
        users = mock(UserRepository.class);
        acl = mock(AclRepository.class);
        aclService = mock(AclService.class);
        ctx = mock(BbsContext.class);
        session = mock(VoidCoreSession.class);

        when(ctx.isSysop()).thenReturn(true);
        when(ctx.session()).thenReturn(session);
        when(session.selectedSysopSlug()).thenReturn("general");
        when(chat.findRoomBySlug("general")).thenReturn(Optional.of(
                new ChatRoom(1L, "general", "General", true, false, true, 1)));
        when(users.listForBoard(9)).thenReturn(List.of(
                new UserRepository.UserSummary(7L, "alice", "", 0, 0, false, null, null)
        ));

        screen = new SysopChatRoomUsersScreen(chat, users, acl, aclService);
    }

    @Test
    void digit_selects_user_and_pushes_detail_phase() {
        screen.onKey(ctx, "1");

        verify(session).setSelectedSysopResourceId(7L);
        verify(ctx).push(io.aeyer.voidcore.ws.flow.screen.Phase.SYSOP_CHAT_ROOM_USER);
    }
}
