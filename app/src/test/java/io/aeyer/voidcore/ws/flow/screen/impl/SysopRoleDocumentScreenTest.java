package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.acl.AclPermission;
import io.aeyer.voidcore.acl.AclPrincipalType;
import io.aeyer.voidcore.acl.AclRepository;
import io.aeyer.voidcore.acl.AclResourceType;
import io.aeyer.voidcore.documents.DocumentKind;
import io.aeyer.voidcore.documents.DocumentRepository;
import io.aeyer.voidcore.documents.DocumentRow;
import io.aeyer.voidcore.documents.Status;
import io.aeyer.voidcore.documents.Visibility;
import io.aeyer.voidcore.ws.VoidCoreSession;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.BbsServices;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SysopRoleDocumentScreenTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    DocumentRepository docs;
    AclRepository acl;
    BbsContext ctx;
    BbsServices services;
    VoidCoreSession session;
    SysopRoleDocumentScreen screen;

    @BeforeEach
    void setUp() {
        docs = mock(DocumentRepository.class);
        acl = mock(AclRepository.class);
        ctx = mock(BbsContext.class);
        services = mock(BbsServices.class);
        session = mock(VoidCoreSession.class);

        when(ctx.isSysop()).thenReturn(true);
        when(ctx.session()).thenReturn(session);
        when(ctx.services()).thenReturn(services);
        when(services.json()).thenReturn(JSON);
        when(session.selectedSysopId()).thenReturn(5L);
        when(session.selectedSysopResourceId()).thenReturn(42L);
        when(docs.findById(42L)).thenReturn(Optional.of(doc(42L, "release", Visibility.PUBLIC)));

        screen = new SysopRoleDocumentScreen(docs, acl);
    }

    @Test
    void eTogglesEditAndAutoGrantsView() {
        when(acl.hasGrant(AclResourceType.DOCUMENT, 42L, AclPermission.EDIT, AclPrincipalType.ROLE, 5L))
                .thenReturn(false);

        screen.onKey(ctx, "E");

        verify(acl).grant(AclResourceType.DOCUMENT, 42L, AclPermission.VIEW, AclPrincipalType.ROLE, 5L);
        verify(acl).grant(AclResourceType.DOCUMENT, 42L, AclPermission.EDIT, AclPrincipalType.ROLE, 5L);
        verify(ctx).publish(io.aeyer.voidcore.ws.flow.view.DocumentView.TOPIC);
    }

    @Test
    void vRevocationAlsoClearsEditGrant() {
        when(acl.hasGrant(AclResourceType.DOCUMENT, 42L, AclPermission.VIEW, AclPrincipalType.ROLE, 5L))
                .thenReturn(true);

        screen.onKey(ctx, "V");

        verify(acl).revoke(AclResourceType.DOCUMENT, 42L, AclPermission.VIEW, AclPrincipalType.ROLE, 5L);
        verify(acl).revoke(AclResourceType.DOCUMENT, 42L, AclPermission.EDIT, AclPrincipalType.ROLE, 5L);
    }

    private static DocumentRow doc(long id, String typeSlug, Visibility visibility) {
        ObjectNode fm = JSON.createObjectNode();
        return new DocumentRow(id, "doc-" + id, "Title-" + id, typeSlug, 1, 1,
                "body", fm, List.of(), 100L, visibility, Status.PUBLISHED,
                OffsetDateTime.now(), OffsetDateTime.now(), null, null, null);
    }
}
