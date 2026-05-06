package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.acl.AclPermission;
import io.aeyer.voidcore.acl.AclResourceType;
import io.aeyer.voidcore.acl.AclService;
import io.aeyer.voidcore.instance.InstanceFeature;
import io.aeyer.voidcore.instance.InstanceFeatureService;
import io.aeyer.voidcore.messages.BoardThread;
import io.aeyer.voidcore.messages.PostRepository;
import io.aeyer.voidcore.messages.ThreadRepository;
import io.aeyer.voidcore.ws.VoidCoreSession;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.BbsServices;
import io.aeyer.voidcore.ws.flow.ui.AppEvent;
import io.aeyer.voidcore.ws.protocol.ServerMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ComposePostScreenTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    ThreadRepository     threads;
    PostRepository       posts;
    AclService           acl;
    BbsContext           ctx;
    BbsServices          services;
    VoidCoreSession         session;
    ComposePostScreen    screen;
    List<ServerMessage>  sent;

    @BeforeEach
    void setUp() {
        threads  = mock(ThreadRepository.class);
        posts    = mock(PostRepository.class);
        acl      = mock(AclService.class);
        ctx      = mock(BbsContext.class);
        services = mock(BbsServices.class);
        session  = mock(VoidCoreSession.class);
        sent     = new ArrayList<>();

        doAnswer(inv -> { sent.add(inv.getArgument(0)); return null; })
            .when(ctx).send(any(ServerMessage.class));

        when(ctx.session()).thenReturn(session);
        when(ctx.services()).thenReturn(services);
        InstanceFeatureService features = mock(InstanceFeatureService.class);
        when(services.instanceFeatures()).thenReturn(features);
        when(features.enabled(InstanceFeature.MESSAGE_BOARD)).thenReturn(true);
        when(services.json()).thenReturn(JSON);
        // socialEvents returns null — no awards path exercised in these tests
        when(services.socialEvents()).thenReturn(null);

        when(session.userId()).thenReturn(10L);
        when(session.selectedThreadId()).thenReturn(5L);
        when(threads.findById(5L)).thenReturn(java.util.Optional.of(
                new BoardThread(5L, 7L, "Subject", "alice", null, null, 1, false, false)));
        when(acl.can(session, AclResourceType.MESSAGE_BASE, 7L, AclPermission.POST)).thenReturn(true);

        screen = new ComposePostScreen(threads, posts, acl);
    }

    @Test
    void editor_commit_creates_post_and_pops() {
        screen.onEnter(ctx);
        screen.onAppEvent(ctx, new AppEvent.EditorCommit("body", "reply text", "save"));

        verify(posts).insert(5L, 10L, "reply text");
        verify(ctx).pop();
    }

    @Test
    void empty_editor_commit_does_not_persist() {
        screen.onEnter(ctx);
        screen.onAppEvent(ctx, new AppEvent.EditorCommit("body", "", "save"));

        verify(posts, never()).insert(anyLong(), anyLong(), anyString());
        verify(ctx, never()).pop();
    }

    @Test
    void editor_cancel_pops() {
        screen.onEnter(ctx);
        screen.onAppEvent(ctx, new AppEvent.EditorCancel("body", false));

        verify(ctx).pop();
    }

    @Test
    void missingPostPermissionDoesNotPersist() {
        when(acl.can(session, AclResourceType.MESSAGE_BASE, 7L, AclPermission.POST)).thenReturn(false);

        screen.onEnter(ctx);
        screen.onAppEvent(ctx, new AppEvent.EditorCommit("body", "reply text", "save"));

        verify(posts, never()).insert(anyLong(), anyLong(), anyString());
        verify(ctx).pop();
    }
}
