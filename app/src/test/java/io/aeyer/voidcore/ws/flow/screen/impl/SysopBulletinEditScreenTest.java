package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.acl.AclService;
import io.aeyer.voidcore.documents.DocumentRepository;
import io.aeyer.voidcore.documents.DocumentRow;
import io.aeyer.voidcore.documents.Status;
import io.aeyer.voidcore.documents.Visibility;
import io.aeyer.voidcore.ws.VoidCoreSession;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.BbsServices;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SysopBulletinEditScreenTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    DocumentRepository docs;
    AclService acl;
    BbsContext ctx;
    BbsServices services;
    VoidCoreSession session;
    SysopBulletinEditScreen screen;
    DocumentRow bulletin;
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

        bulletin = new DocumentRow(
                5L, "bulletin-5", "Welcome", "article", 1, 1, "Hello world",
                JsonNodeFactory.instance.objectNode(),
                List.of(), 1L, Visibility.PUBLIC, Status.PUBLISHED,
                OffsetDateTime.parse("2026-05-04T12:00:00Z"),
                OffsetDateTime.parse("2026-05-04T12:00:00Z"),
                null, null, (UUID) null);

        when(ctx.isSysop()).thenReturn(true);
        when(ctx.session()).thenReturn(session);
        when(ctx.services()).thenReturn(services);
        when(session.selectedSysopId()).thenReturn(5L);
        when(services.json()).thenReturn(JSON);
        when(docs.findArticleByIdOrLegacyBulletinId(5L)).thenReturn(Optional.of(bulletin));

        screen = new SysopBulletinEditScreen(docs, acl);
    }

    @Test
    void title_commit_updates_title() {
        screen.onEnter(ctx);
        screen.onKey(ctx, "E");
        screen.onKey(ctx, "T");
        screen.onAppEvent(ctx, new io.aeyer.voidcore.ws.flow.ui.AppEvent.FieldCommit("T", "New title"));

        verify(docs).updateTitle(5L, "New title");
    }

    @Test
    void K_moves_bulletin_up() {
        when(docs.moveArticle(5L, -1)).thenReturn(true);

        screen.onEnter(ctx);
        screen.onKey(ctx, "K");

        verify(docs).moveArticle(5L, -1);
    }
}
