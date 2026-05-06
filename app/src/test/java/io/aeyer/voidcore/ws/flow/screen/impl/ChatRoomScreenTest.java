package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.acl.AclPermission;
import io.aeyer.voidcore.acl.AclResourceType;
import io.aeyer.voidcore.acl.AclService;
import io.aeyer.voidcore.auth.RateLimitDecision;
import io.aeyer.voidcore.auth.RateLimiter;
import io.aeyer.voidcore.auth.UserRepository;
import io.aeyer.voidcore.auth.UserRepository.UserRow;
import io.aeyer.voidcore.chat.ChatRepository;
import io.aeyer.voidcore.chat.ChatRoom;
import io.aeyer.voidcore.instance.InstanceFeature;
import io.aeyer.voidcore.instance.InstanceFeatureService;
import io.aeyer.voidcore.ws.VoidCoreSession;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.BbsServices;
import io.aeyer.voidcore.ws.flow.screen.MentionService;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.flow.view.ChatView;
import io.aeyer.voidcore.ws.protocol.ServerMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatRoomScreenTest {

    private static final ChatRoom GENERAL_ROOM = new ChatRoom(1L, "general", "sysop", false, true, 1);

    ChatRepository repo;
    AclService acl;
    RateLimiter rateLimiter;
    UserRepository users;
    BbsContext ctx;
    BbsServices services;
    VoidCoreSession session;
    ChatView chatView;
    MentionService mentions;
    ChatRoomScreen screen;
    List<ServerMessage> sent;

    @BeforeEach
    void setUp() {
        repo = mock(ChatRepository.class);
        acl = mock(AclService.class);
        rateLimiter = mock(RateLimiter.class);
        users = mock(UserRepository.class);
        ctx = mock(BbsContext.class);
        services = mock(BbsServices.class);
        session = mock(VoidCoreSession.class);
        chatView = mock(ChatView.class);
        mentions = mock(MentionService.class);
        sent = new ArrayList<>();

        doAnswer(inv -> { sent.add(inv.getArgument(0)); return null; })
                .when(ctx).send(any(ServerMessage.class));

        when(ctx.session()).thenReturn(session);
        when(ctx.services()).thenReturn(services);
        when(services.chat()).thenReturn(chatView);
        when(services.mentions()).thenReturn(mentions);
        InstanceFeatureService features = mock(InstanceFeatureService.class);
        when(services.instanceFeatures()).thenReturn(features);
        when(features.enabled(InstanceFeature.CHAT)).thenReturn(true);
        when(session.userId()).thenReturn(7L);
        when(session.currentChatRoomSlug()).thenReturn("general");
        when(repo.findActiveRoomBySlug("general")).thenReturn(Optional.of(GENERAL_ROOM));
        when(acl.can(session, AclResourceType.CHAT_ROOM, 1L, AclPermission.VIEW)).thenReturn(true);
        when(acl.can(session, AclResourceType.CHAT_ROOM, 1L, AclPermission.POST)).thenReturn(true);
        when(chatView.recent("general")).thenReturn(List.of());
        when(rateLimiter.checkAndRecordPost(7L, RateLimiter.PostKind.CHAT))
                .thenReturn(RateLimitDecision.ALLOWED);
        when(users.findById(7L)).thenReturn(Optional.of(new UserRow(7L, "sysop", "pw", false, false)));

        screen = new ChatRoomScreen(repo, acl, rateLimiter, users);
    }

    @Test
    void onEnter_persists_room_screen_and_emits_prompt() {
        screen.onEnter(ctx);

        verify(ctx).persistCurrentScreen("{\"kind\":\"chat_room\",\"room\":\"general\"}");
        verify(ctx).send(any(ServerMessage.InputPrompt.class));
    }

    @Test
    void line_submit_inserts_room_message_and_publishes_room_topic() {
        screen.onLine(ctx, "hello room");

        verify(repo).insert(1L, 7L, "hello room", ChatRepository.KIND_MSG);
        verify(ctx).publish(ChatView.topicFor("general"));
        verify(mentions).notify(eq(session), eq("hello room"), eq("chat #general"), eq(Phase.CHAT_ROOM));
        verify(ctx).send(any(ServerMessage.InputPrompt.class));
    }

    @Test
    void room_disable_event_boots_viewer_back_to_room_list() {
        when(repo.findActiveRoomBySlug("general")).thenReturn(Optional.empty());

        screen.onEvent(ctx, ChatView.topicFor("general"));

        verify(session).setCurrentChatRoomSlug(null);
        verify(ctx).pop();
        verify(repo, never()).insert(anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    void msg_creates_or_reuses_dm_room_posts_message_and_switches_there() {
        ChatRoom dm = new ChatRoom(5L, "bob-sysop-dm", "DM · bob / sysop", true, true, true, 5);
        when(users.findByHandle("bob")).thenReturn(Optional.of(new UserRow(9L, "bob", "pw", false, false)));
        when(repo.findRoomBySlug("bob-sysop-dm")).thenReturn(Optional.empty());
        when(repo.createRoom("bob-sysop-dm", "DM · bob / sysop", true, true)).thenReturn(5L);
        when(repo.findActiveRoomBySlug("bob-sysop-dm")).thenReturn(Optional.of(dm));
        when(repo.listRoomParticipantIds(5L)).thenReturn(List.of(7L, 9L));

        screen.onLine(ctx, "/msg bob meet me at the docks");

        verify(repo).createRoom("bob-sysop-dm", "DM · bob / sysop", true, true);
        verify(repo).addRoomMember(5L, 7L);
        verify(repo).addRoomMember(5L, 9L);
        verify(repo).insert(5L, 7L, "meet me at the docks", ChatRepository.KIND_MSG);
        verify(ctx, atLeastOnce()).publish(ChatView.ROOMS_TOPIC);
        verify(ctx).publish(ChatView.topicFor("bob-sysop-dm"));
        verify(session).setCurrentChatRoomSlug("bob-sysop-dm");
        verify(mentions).notifyUser(eq(9L), eq(null), anyString(), eq(5000));
    }
}
