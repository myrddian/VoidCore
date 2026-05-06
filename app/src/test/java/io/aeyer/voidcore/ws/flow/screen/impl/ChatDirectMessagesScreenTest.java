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

class ChatDirectMessagesScreenTest {

    ChatRepository repo;
    AclService acl;
    BbsContext ctx;
    BbsServices services;
    VoidCoreSession session;
    ChatDirectMessagesScreen screen;
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
        when(repo.listActiveDirectRoomsForUser(7L)).thenReturn(List.of(
                new ChatRoom(5L, "bob-sysop-dm", "DM · bob / SYSOP", true, true, true, 5)));
        when(acl.can(session, AclResourceType.CHAT_ROOM, 5L, AclPermission.VIEW)).thenReturn(true);

        screen = new ChatDirectMessagesScreen(repo, acl);
    }

    @Test
    void onEnter_persists_chat_directs() {
        screen.onEnter(ctx);

        verify(session).setCurrentChatRoomSlug(null);
        verify(ctx).persistCurrentScreen("{\"kind\":\"chat_directs\"}");
        verify(ctx).send(any(ServerMessage.RegionUpdate.class));
        verify(ctx).send(any(ServerMessage.InputPrompt.class));
    }

    @Test
    void numeric_selection_sets_room_slug_and_pushes_chat_room() {
        screen.onKey(ctx, "1");

        verify(session).setCurrentChatRoomSlug("bob-sysop-dm");
        verify(ctx).push(Phase.CHAT_ROOM);
    }

    @Test
    void N_opens_new_direct_message_prompt() {
        screen.onKey(ctx, "N");

        verify(ctx).push(Phase.CHAT_DIRECT_NEW);
    }
}
