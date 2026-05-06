package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.acl.AclPermission;
import io.aeyer.voidcore.acl.AclPrincipalType;
import io.aeyer.voidcore.acl.AclResourceType;
import io.aeyer.voidcore.acl.AclService;
import io.aeyer.voidcore.chat.ChatRepository;
import io.aeyer.voidcore.chat.ChatRoom;
import io.aeyer.voidcore.ws.VoidCoreSession;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.BbsServices;
import io.aeyer.voidcore.ws.flow.ui.AppEvent;
import io.aeyer.voidcore.ws.flow.view.ChatView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SysopChatRoomNewScreenTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    ChatRepository repo;
    AclService acl;
    BbsContext ctx;
    BbsServices services;
    VoidCoreSession session;
    SysopChatRoomNewScreen screen;

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
        when(repo.findRoomBySlug("synth")).thenReturn(Optional.empty());
        when(repo.createRoom("synth", "Synth Lab", false)).thenReturn(9L);

        screen = new SysopChatRoomNewScreen(repo, acl);
    }

    @Test
    void wizard_creates_room_and_publishes_room_list_topic() {
        screen.onEnter(ctx);
        screen.onAppEvent(ctx, new AppEvent.FieldCommit("step", "synth"));
        screen.onAppEvent(ctx, new AppEvent.FieldCommit("step", "Synth Lab"));

        verify(repo).createRoom("synth", "Synth Lab", false);
        verify(acl).grant(AclResourceType.CHAT_ROOM, 9L, AclPermission.VIEW, AclPrincipalType.AUTHENTICATED, null);
        verify(acl).grant(AclResourceType.CHAT_ROOM, 9L, AclPermission.POST, AclPrincipalType.AUTHENTICATED, null);
        verify(acl).grant(AclResourceType.CHAT_ROOM, 9L, AclPermission.MANAGE, AclPrincipalType.SYSOP, null);
        verify(acl).grantRoleIfPresent(AclResourceType.CHAT_ROOM, 9L, AclPermission.MANAGE, "ADMIN");
        verify(acl).grantRoleIfPresent(AclResourceType.CHAT_ROOM, 9L, AclPermission.MANAGE, "MODERATOR");
        verify(ctx).publish(ChatView.ROOMS_TOPIC);
        verify(ctx).pop();
    }

    @Test
    void duplicate_slug_rejects_first_step() {
        when(repo.findRoomBySlug("general")).thenReturn(Optional.of(
                new ChatRoom(1L, "general", "General", false, true, 1)));

        screen.onEnter(ctx);
        screen.onAppEvent(ctx, new AppEvent.FieldCommit("step", "general"));

        assertThat(screen.currentStepIndex()).isEqualTo(0);
    }
}
