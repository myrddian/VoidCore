package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.acl.AclPermission;
import io.aeyer.voidcore.acl.AclResourceType;
import io.aeyer.voidcore.acl.AclService;
import io.aeyer.voidcore.chat.ChatRepository;
import io.aeyer.voidcore.chat.ChatRoom;
import io.aeyer.voidcore.instance.InstanceFeature;
import io.aeyer.voidcore.instance.InstanceFeatureService;
import io.aeyer.voidcore.ws.VoidCoreSession;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.BbsServices;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.protocol.ServerMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatRoomsScreenTest {

    ChatRepository repo;
    AclService acl;
    BbsContext ctx;
    BbsServices services;
    VoidCoreSession session;
    ChatRoomsScreen screen;
    List<ServerMessage> sent;

    @BeforeEach
    void setUp() {
        repo = mock(ChatRepository.class);
        acl = mock(AclService.class);
        ctx = mock(BbsContext.class);
        services = mock(BbsServices.class);
        session = mock(VoidCoreSession.class);
        sent = new ArrayList<>();

        doAnswer(inv -> { sent.add(inv.getArgument(0)); return null; })
                .when(ctx).send(any(ServerMessage.class));

        when(ctx.session()).thenReturn(session);
        when(ctx.services()).thenReturn(services);
        InstanceFeatureService features = mock(InstanceFeatureService.class);
        when(services.instanceFeatures()).thenReturn(features);
        when(features.enabled(InstanceFeature.CHAT)).thenReturn(true);
        when(session.userId()).thenReturn(7L);
        when(repo.listActiveRooms()).thenReturn(List.of(
                new ChatRoom(1L, "general", "General", false, true, 1),
                new ChatRoom(2L, "meta", "Meta", false, true, 2)));
        when(acl.can(session, AclResourceType.CHAT_ROOM, 1L, AclPermission.VIEW)).thenReturn(true);
        when(acl.can(session, AclResourceType.CHAT_ROOM, 2L, AclPermission.VIEW)).thenReturn(true);

        screen = new ChatRoomsScreen(repo, acl);
    }

    @Test
    void onEnter_clears_current_room_and_persists_chat_rooms() {
        screen.onEnter(ctx);

        verify(session).setCurrentChatRoomSlug(null);
        verify(ctx).persistCurrentScreen("{\"kind\":\"chat_rooms\"}");
        verify(ctx).send(any(ServerMessage.RegionUpdate.class));
        verify(ctx).send(any(ServerMessage.InputPrompt.class));
    }

    @Test
    void numeric_selection_sets_room_slug_and_pushes_room_phase() {
        screen.onKey(ctx, "2");

        verify(session).setCurrentChatRoomSlug("meta");
        verify(ctx).push(Phase.CHAT_ROOM);
    }

    @Test
    void M_opens_direct_messages_picker() {
        screen.onKey(ctx, "M");

        verify(ctx).push(Phase.CHAT_DIRECTS);
    }
}
