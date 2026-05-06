package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.acl.AclService;
import io.aeyer.voidcore.auth.UserRepository;
import io.aeyer.voidcore.auth.UserRepository.UserRow;
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
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatDirectMessageNewScreenTest {

    ChatRepository repo;
    AclService acl;
    UserRepository users;
    BbsContext ctx;
    BbsServices services;
    VoidCoreSession session;
    ChatDirectMessageNewScreen screen;
    List<ServerMessage> sent;

    @BeforeEach
    void setUp() {
        repo = mock(ChatRepository.class);
        acl = mock(AclService.class);
        users = mock(UserRepository.class);
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
        when(users.findById(7L)).thenReturn(Optional.of(new UserRow(7L, "SYSOP", "pw", false, false)));
        when(users.findByHandle("bob")).thenReturn(Optional.of(new UserRow(9L, "bob", "pw", false, false)));
        when(repo.findRoomBySlug("bob-sysop-dm")).thenReturn(Optional.empty());
        when(repo.createRoom("bob-sysop-dm", "DM · bob / SYSOP", true, true)).thenReturn(5L);
        when(repo.findActiveRoomBySlug("bob-sysop-dm"))
                .thenReturn(Optional.of(new ChatRoom(5L, "bob-sysop-dm", "DM · bob / SYSOP", true, true, true, 5)));

        screen = new ChatDirectMessageNewScreen(repo, acl, users);
    }

    @Test
    void line_opens_dm_room_and_pushes_chat_room() {
        screen.onLine(ctx, "bob");

        verify(session).setCurrentChatRoomSlug("bob-sysop-dm");
        verify(ctx).persistCurrentScreen("{\"kind\":\"chat_room\",\"room\":\"bob-sysop-dm\"}");
        verify(ctx).pop();
        verify(ctx).push(Phase.CHAT_ROOM);
    }
}
