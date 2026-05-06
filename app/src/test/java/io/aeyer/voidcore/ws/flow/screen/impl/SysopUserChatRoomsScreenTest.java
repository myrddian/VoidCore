package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.acl.AclRepository;
import io.aeyer.voidcore.auth.UserRepository;
import io.aeyer.voidcore.auth.UserRepository.UserRow;
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

class SysopUserChatRoomsScreenTest {

    UserRepository users;
    ChatRepository chat;
    AclRepository acl;
    BbsContext ctx;
    VoidCoreSession session;
    UserRow user;
    SysopUserChatRoomsScreen screen;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        chat = mock(ChatRepository.class);
        acl = mock(AclRepository.class);
        ctx = mock(BbsContext.class);
        session = mock(VoidCoreSession.class);
        user = mock(UserRow.class);

        when(ctx.isSysop()).thenReturn(true);
        when(ctx.session()).thenReturn(session);
        when(session.selectedSysopId()).thenReturn(7L);
        when(user.handle()).thenReturn("alice");
        when(users.findById(7L)).thenReturn(Optional.of(user));
        when(chat.listAllRooms()).thenReturn(List.of(
                new ChatRoom(1L, "general", "General", false, false, true, 1)
        ));

        screen = new SysopUserChatRoomsScreen(users, chat, acl);
    }

    @Test
    void digit_selects_room_and_pushes_detail_phase() {
        screen.onKey(ctx, "1");

        verify(session).setSelectedSysopSlug("general");
        verify(ctx).push(io.aeyer.voidcore.ws.flow.screen.Phase.SYSOP_USER_CHAT_ROOM);
    }
}
