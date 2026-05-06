package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.acl.AclPermission;
import io.aeyer.voidcore.acl.AclResourceType;
import io.aeyer.voidcore.acl.AclService;
import io.aeyer.voidcore.instance.InstanceFeature;
import io.aeyer.voidcore.instance.InstanceFeatureService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ComposeThreadScreenTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    ThreadRepository   threads;
    PostRepository     posts;
    AclService         acl;
    BbsContext         ctx;
    BbsServices        services;
    VoidCoreSession       session;
    ComposeThreadScreen screen;
    List<ServerMessage> sent;

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

        when(session.userId()).thenReturn(42L);
        when(session.selectedBaseId()).thenReturn(7L);
        when(acl.can(session, AclResourceType.MESSAGE_BASE, 7L, AclPermission.POST)).thenReturn(true);
        when(threads.insert(anyLong(), anyString(), anyLong())).thenReturn(100L);
        when(posts.insert(anyLong(), anyLong(), anyString())).thenReturn(200L);

        screen = new ComposeThreadScreen(threads, posts, acl);
    }

    @Test
    void wizard_creates_thread_after_subject_and_body() {
        screen.onEnter(ctx);

        // Step 0: commit subject
        screen.onAppEvent(ctx, new AppEvent.FieldCommit("step", "My new thread"));

        // Step 1: commit body via editor
        screen.onAppEvent(ctx, new AppEvent.EditorCommit("step", "Here is the body", "save"));

        verify(threads).insert(eq(7L), eq("My new thread"), eq(42L));
        verify(posts).insert(eq(100L), eq(42L), eq("Here is the body"));
        verify(ctx).replaceTopAndEnter(any());
    }

    @Test
    void empty_subject_does_not_advance() {
        screen.onEnter(ctx);

        screen.onAppEvent(ctx, new AppEvent.FieldCommit("step", ""));

        assertThat(screen.currentStepIndex()).isEqualTo(0);
        verify(threads, never()).insert(anyLong(), anyString(), anyLong());
        verify(posts,   never()).insert(anyLong(), anyLong(), anyString());
    }

    @Test
    void empty_body_does_not_advance() {
        screen.onEnter(ctx);

        // Advance past step 0 with a valid subject
        screen.onAppEvent(ctx, new AppEvent.FieldCommit("step", "Good Subject"));
        assertThat(screen.currentStepIndex()).isEqualTo(1);

        // Submit an empty body — should stay on step 1
        screen.onAppEvent(ctx, new AppEvent.EditorCommit("step", "", "save"));

        assertThat(screen.currentStepIndex()).isEqualTo(1);
        verify(threads, never()).insert(anyLong(), anyString(), anyLong());
        verify(posts,   never()).insert(anyLong(), anyLong(), anyString());
    }

    @Test
    void missingPostPermissionDoesNotCreateThread() {
        when(acl.can(session, AclResourceType.MESSAGE_BASE, 7L, AclPermission.POST)).thenReturn(false);

        screen.onEnter(ctx);
        screen.onAppEvent(ctx, new AppEvent.FieldCommit("step", "My new thread"));
        screen.onAppEvent(ctx, new AppEvent.EditorCommit("step", "Here is the body", "save"));

        verify(threads, never()).insert(anyLong(), anyString(), anyLong());
        verify(posts, never()).insert(anyLong(), anyLong(), anyString());
        verify(ctx, never()).replaceTopAndEnter(any());
    }
}
