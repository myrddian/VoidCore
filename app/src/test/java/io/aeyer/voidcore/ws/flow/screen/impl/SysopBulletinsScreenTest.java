package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.acl.AclPermission;
import io.aeyer.voidcore.acl.AclResourceType;
import io.aeyer.voidcore.acl.AclService;
import io.aeyer.voidcore.documents.DocumentRepository;
import io.aeyer.voidcore.documents.DocumentRow;
import io.aeyer.voidcore.documents.Status;
import io.aeyer.voidcore.documents.Visibility;
import io.aeyer.voidcore.ws.VoidCoreSession;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.BbsServices;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.flow.view.DocumentView;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SysopBulletinsScreenTest {

    DocumentRepository docs;
    AclService acl;
    DocumentView documents;
    BbsServices services;
    BbsContext ctx;
    VoidCoreSession session;
    SysopBulletinsScreen screen;

    @BeforeEach
    void setUp() {
        docs = mock(DocumentRepository.class);
        acl = mock(AclService.class);
        documents = mock(DocumentView.class);
        services = mock(BbsServices.class);
        ctx = mock(BbsContext.class);
        session = mock(VoidCoreSession.class);

        when(ctx.isSysop()).thenReturn(true);
        when(ctx.session()).thenReturn(session);
        when(ctx.services()).thenReturn(services);
        when(services.documents()).thenReturn(documents);
        when(session.selectedSysopId()).thenReturn(-3L);
        when(documents.list()).thenReturn(List.of(
                new DocumentRow(
                        7L,
                        "welcome",
                        "Welcome",
                        "article",
                        1,
                        1,
                        "Body",
                        JsonNodeFactory.instance.objectNode(),
                        List.of(),
                        1L,
                        Visibility.PUBLIC,
                        Status.PUBLISHED,
                        OffsetDateTime.parse("2026-05-04T12:00:00Z"),
                        OffsetDateTime.parse("2026-05-04T12:00:00Z"),
                        null,
                        null,
                        (UUID) null)
        ));
        when(acl.can(session, AclResourceType.DOCUMENT, 7L, AclPermission.MANAGE)).thenReturn(true);

        screen = new SysopBulletinsScreen(docs, acl);
    }

    @Test
    void E_then_digit_pushes_edit_screen() {
        screen.onKey(ctx, "E");
        screen.onKey(ctx, "1");

        var order = inOrder(session, ctx);
        order.verify(session).setSelectedSysopId(-3L);
        order.verify(session).setSelectedSysopId(7L);
        verify(ctx).push(Phase.SYSOP_BULLETIN_EDIT);
    }
}
