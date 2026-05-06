package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.acl.AclPermission;
import io.aeyer.voidcore.acl.AclResourceType;
import io.aeyer.voidcore.acl.AclService;
import io.aeyer.voidcore.documents.DocumentRepository;
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

class SysopBulletinNewScreenTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    DocumentRepository docs;
    AclService acl;
    BbsContext ctx;
    BbsServices services;
    VoidCoreSession session;
    SysopBulletinNewScreen screen;
    List<ServerMessage> sent;

    @BeforeEach
    void setUp() {
        docs = mock(DocumentRepository.class);
        acl = mock(AclService.class);
        ctx       = mock(BbsContext.class);
        services  = mock(BbsServices.class);
        session   = mock(VoidCoreSession.class);
        sent      = new ArrayList<>();
        doAnswer(inv -> { sent.add(inv.getArgument(0)); return null; })
            .when(ctx).send(any(ServerMessage.class));

        when(ctx.session()).thenReturn(session);
        when(ctx.services()).thenReturn(services);
        when(ctx.isSysop()).thenReturn(true);
        when(services.json()).thenReturn(JSON);
        when(session.userId()).thenReturn(7L);
        when(docs.insertWithTypeSlug(anyString(), anyString(), anyString(), anyString(),
                any(), any(), anyLong(), any(), any())).thenReturn(99L);

        screen = new SysopBulletinNewScreen(docs, acl);
    }

    @Test
    void wizard_creates_bulletin_after_title_and_body() {
        screen.onEnter(ctx);
        screen.onAppEvent(ctx, new AppEvent.FieldCommit("step", "Welcome"));
        screen.onAppEvent(ctx, new AppEvent.EditorCommit("step", "Hello world", "save"));
        verify(docs).insertWithTypeSlug(anyString(), eq("Welcome"), eq("article"),
                eq("Hello world"), any(), any(), eq(7L), any(), any());
        verify(acl).grantRoleIfPresent(AclResourceType.DOCUMENT, 99L, AclPermission.MANAGE, "ADMIN");
        verify(acl).grantRoleIfPresent(AclResourceType.DOCUMENT, 99L, AclPermission.VIEW, "MODERATOR");
        verify(acl).grantRoleIfPresent(AclResourceType.DOCUMENT, 99L, AclPermission.EDIT, "MODERATOR");
        verify(ctx).pop();
    }

    @Test
    void empty_title_does_not_advance() {
        screen.onEnter(ctx);
        screen.onAppEvent(ctx, new AppEvent.FieldCommit("step", ""));
        assertThat(screen.currentStepIndex()).isEqualTo(0);
        verify(docs, never()).insertWithTypeSlug(anyString(), anyString(), anyString(),
                anyString(), any(), any(), anyLong(), any(), any());
    }

    @Test
    void pinned_prefix_sets_pinned_flag_and_strips_prefix() {
        screen.onEnter(ctx);
        screen.onAppEvent(ctx, new AppEvent.FieldCommit("step", "P: Important Notice"));
        screen.onAppEvent(ctx, new AppEvent.EditorCommit("step", "Read this", "save"));
        verify(docs).insertWithTypeSlug(anyString(), eq("Important Notice"), eq("article"),
                eq("Read this"), any(), any(), eq(7L), any(), any());
        verify(acl).grantRoleIfPresent(AclResourceType.DOCUMENT, 99L, AclPermission.MANAGE, "ADMIN");
        verify(ctx).pop();
    }
}
