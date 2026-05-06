package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.acl.AclPermission;
import io.aeyer.voidcore.acl.AclResourceType;
import io.aeyer.voidcore.acl.AclService;
import io.aeyer.voidcore.chat.ChatRepository;
import io.aeyer.voidcore.chat.ChatRoom;
import io.aeyer.voidcore.ws.VoidCoreSession;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.BbsServices;
import io.aeyer.voidcore.ws.flow.view.ChatView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SysopChatRoomsScreenTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    ChatRepository repo;
    AclService acl;
    BbsContext ctx;
    BbsServices services;
    VoidCoreSession session;
    SysopChatRoomsScreen screen;

    @BeforeEach
    void setUp() {
        repo = mock(ChatRepository.class);
        acl = mock(AclService.class);
        ctx = mock(BbsContext.class);
        services = mock(BbsServices.class);
        session = mock(VoidCoreSession.class);

        when(ctx.isSysop()).thenReturn(true);
        when(ctx.session()).thenReturn(session);
        when(ctx.services()).thenReturn(services);
        when(services.json()).thenReturn(JSON);
        when(session.selectedSysopId()).thenReturn(-11L);
        when(repo.listAllRooms()).thenReturn(List.of(
                new ChatRoom(1L, "general", "General", false, true, 1)
        ));
        when(acl.can(session, AclResourceType.CHAT_ROOM, 1L, AclPermission.MANAGE)).thenReturn(true);

        screen = new SysopChatRoomsScreen(repo, acl);
    }

    @Test
    void T_then_digit_disables_room_and_publishes_updates() {
        screen.onKey(ctx, "T");
        screen.onKey(ctx, "1");

        verify(repo).setRoomActive(1L, false);
        verify(ctx).publish(ChatView.ROOMS_TOPIC);
        verify(ctx).publish(ChatView.topicFor("general"));
        var order = inOrder(session);
        order.verify(session).setSelectedSysopId(-11L);
        order.verify(session).setSelectedSysopId(null);
    }
}
