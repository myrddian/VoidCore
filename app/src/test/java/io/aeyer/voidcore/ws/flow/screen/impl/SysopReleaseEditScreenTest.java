package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.acl.AclService;
import io.aeyer.voidcore.documents.DocumentRepository;
import io.aeyer.voidcore.documents.DocumentRow;
import io.aeyer.voidcore.documents.Status;
import io.aeyer.voidcore.documents.Visibility;
import io.aeyer.voidcore.ws.VoidCoreSession;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.BbsServices;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.flow.ui.AppEvent;
import io.aeyer.voidcore.ws.protocol.ServerMessage;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SysopReleaseEditScreenTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    DocumentRepository docs;
    AclService acl;
    BbsContext ctx;
    BbsServices services;
    VoidCoreSession session;
    SysopReleaseEditScreen screen;
    DocumentRow file;
    List<ServerMessage> sent;

    @BeforeEach
    void setUp() {
        docs   = mock(DocumentRepository.class);
        acl    = mock(AclService.class);
        ctx      = mock(BbsContext.class);
        services = mock(BbsServices.class);
        session  = mock(VoidCoreSession.class);
        sent     = new ArrayList<>();

        doAnswer(inv -> { sent.add(inv.getArgument(0)); return null; })
            .when(ctx).send(any(ServerMessage.class));

        var fm = JsonNodeFactory.instance.objectNode();
        fm.put("filename", "led_zeppelin_iv.zip");
        fm.put("size_bytes", 1024L);
        fm.put("download_count", 0);
        fm.put("year", 1971);
        fm.put("artist", "Led Zeppelin");
        fm.put("label", "Atlantic");
        fm.put("catalog_number", "SD 7208");
        fm.put("genre", "Rock");
        file = new DocumentRow(
            42L,
            "led-zeppelin-iv",
            "Old Title",
            "release",
            1,
            1,
            "",
            fm,
            List.of(),
            7L,
            Visibility.PUBLIC,
            Status.PUBLISHED,
            OffsetDateTime.now(),
            OffsetDateTime.now(),
            null,
            null,
            (UUID) null
        );

        when(ctx.isSysop()).thenReturn(true);
        when(ctx.session()).thenReturn(session);
        when(ctx.services()).thenReturn(services);
        when(session.selectedSysopId()).thenReturn(42L);
        when(services.json()).thenReturn(JSON);
        when(docs.findReleaseByIdOrLegacyFileId(42L)).thenReturn(Optional.of(file));

        screen = new SysopReleaseEditScreen(docs, acl);
    }

    @Test
    void Q_in_VIEW_pops_screen() {
        screen.onEnter(ctx);
        screen.onKey(ctx, "Q");
        verify(ctx).pop();
    }

    @Test
    void E_then_T_then_FieldCommit_updates_title() {
        screen.onEnter(ctx);
        screen.onKey(ctx, "E");
        screen.onKey(ctx, "T");
        screen.onAppEvent(ctx, new AppEvent.FieldCommit("T", "New Title"));
        verify(docs).updateTitle(42L, "New Title");
    }

    @Test
    void empty_title_commit_does_not_persist() {
        screen.onEnter(ctx);
        screen.onKey(ctx, "E");
        screen.onKey(ctx, "T");
        screen.onAppEvent(ctx, new AppEvent.FieldCommit("T", ""));
        verify(docs, never()).updateTitle(anyLong(), anyString());
        // Verify an alert notification was sent
        assertThat(sent).anyMatch(msg ->
            msg instanceof ServerMessage.RegionNotify n
                && "alert".equals(n.level())
                && n.content() != null
                && n.content().stream().anyMatch(row ->
                    row.spans().stream().anyMatch(span ->
                        span.text() != null && span.text().contains("title cannot be empty"))));
    }

    @Test
    void D_pushes_delete_confirm_phase() {
        screen.onEnter(ctx);
        screen.onKey(ctx, "E");
        screen.onKey(ctx, "D");
        verify(ctx).push(Phase.SYSOP_RELEASE_DELETE_CONFIRM);
    }
}
