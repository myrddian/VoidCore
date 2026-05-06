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

class SysopReleaseNewScreenTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    DocumentRepository docs;
    AclService acl;
    BbsContext ctx;
    BbsServices services;
    VoidCoreSession session;
    SysopReleaseNewScreen screen;
    List<ServerMessage> sent;

    @BeforeEach
    void setUp() {
        docs = mock(DocumentRepository.class);
        acl = mock(AclService.class);
        ctx = mock(BbsContext.class);
        services = mock(BbsServices.class);
        session = mock(VoidCoreSession.class);
        sent = new ArrayList<>();
        doAnswer(inv -> { sent.add(inv.getArgument(0)); return null; })
            .when(ctx).send(any(ServerMessage.class));

        when(ctx.session()).thenReturn(session);
        when(ctx.services()).thenReturn(services);
        when(ctx.isSysop()).thenReturn(true);
        when(services.json()).thenReturn(JSON);
        when(session.userId()).thenReturn(7L);
        when(docs.insertWithTypeSlug(anyString(), anyString(), anyString(), anyString(),
                any(), any(), anyLong(), any(), any())).thenReturn(99L);

        screen = new SysopReleaseNewScreen(docs, acl);
    }

    @Test
    void wizard_creates_file_after_all_nine_steps() {
        screen.onEnter(ctx);
        screen.onAppEvent(ctx, new AppEvent.FieldCommit("step", "NEWREL.ZIP"));
        screen.onAppEvent(ctx, new AppEvent.FieldCommit("step", "New Release"));
        screen.onAppEvent(ctx, new AppEvent.FieldCommit("step", "VA"));
        screen.onAppEvent(ctx, new AppEvent.FieldCommit("step", "1999"));
        screen.onAppEvent(ctx, new AppEvent.FieldCommit("step", ""));
        screen.onAppEvent(ctx, new AppEvent.FieldCommit("step", ""));
        screen.onAppEvent(ctx, new AppEvent.FieldCommit("step", "Demo"));
        screen.onAppEvent(ctx, new AppEvent.FieldCommit("step", ""));
        screen.onAppEvent(ctx, new AppEvent.EditorCommit("step", "release notes", "save"));
        verify(docs).insertWithTypeSlug(anyString(), eq("New Release"), eq("release"),
                eq("release notes"), any(), any(), eq(7L), any(), any());
        verify(acl).grantRoleIfPresent(AclResourceType.DOCUMENT, 99L, AclPermission.MANAGE, "ADMIN");
        verify(ctx).pop();
    }

    @Test
    void invalid_filename_rejects_step_0() {
        screen.onEnter(ctx);
        screen.onAppEvent(ctx, new AppEvent.FieldCommit("step", "with space.zip"));
        assertThat(screen.currentStepIndex()).isEqualTo(0);
        verify(docs, never()).insertWithTypeSlug(anyString(), anyString(), anyString(),
                anyString(), any(), any(), anyLong(), any(), any());
    }

    @Test
    void invalid_year_rejects_step_3() {
        screen.onEnter(ctx);
        screen.onAppEvent(ctx, new AppEvent.FieldCommit("step", "OK.ZIP"));
        screen.onAppEvent(ctx, new AppEvent.FieldCommit("step", "Title"));
        screen.onAppEvent(ctx, new AppEvent.FieldCommit("step", ""));   // artist
        screen.onAppEvent(ctx, new AppEvent.FieldCommit("step", "not a number"));   // year
        assertThat(screen.currentStepIndex()).isEqualTo(3);
    }
}
