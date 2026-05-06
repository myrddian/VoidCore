package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.acl.AclPermission;
import io.aeyer.voidcore.acl.AclPrincipalType;
import io.aeyer.voidcore.acl.AclRepository;
import io.aeyer.voidcore.acl.AclResourceType;
import io.aeyer.voidcore.messages.MessageBase;
import io.aeyer.voidcore.messages.MessageBaseRepository;
import io.aeyer.voidcore.ws.VoidCoreSession;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.BbsServices;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SysopRoleMessageBaseScreenTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    MessageBaseRepository bases;
    AclRepository acl;
    BbsContext ctx;
    BbsServices services;
    VoidCoreSession session;
    SysopRoleMessageBaseScreen screen;

    @BeforeEach
    void setUp() {
        bases = mock(MessageBaseRepository.class);
        acl = mock(AclRepository.class);
        ctx = mock(BbsContext.class);
        services = mock(BbsServices.class);
        session = mock(VoidCoreSession.class);

        when(ctx.isSysop()).thenReturn(true);
        when(ctx.session()).thenReturn(session);
        when(ctx.services()).thenReturn(services);
        when(services.json()).thenReturn(JSON);
        when(session.selectedSysopId()).thenReturn(5L);
        when(session.selectedSysopResourceId()).thenReturn(7L);
        when(bases.findById(7L)).thenReturn(Optional.of(
                new MessageBase(7L, "general", "General", "", 1, false)));

        screen = new SysopRoleMessageBaseScreen(bases, acl);
    }

    @Test
    void vTogglesViewAndClearsPostGrantToo() {
        when(acl.hasGrant(AclResourceType.MESSAGE_BASE, 7L, AclPermission.VIEW, AclPrincipalType.ROLE, 5L))
                .thenReturn(false);

        screen.onKey(ctx, "V");

        verify(acl).grant(AclResourceType.MESSAGE_BASE, 7L, AclPermission.VIEW, AclPrincipalType.ROLE, 5L);
        verify(ctx).publish(BasesListScreen.TOPIC);
        verify(ctx).publish(ThreadsListScreen.topicFor(7L));
    }

    @Test
    void pTogglesPostAndAutoGrantsView() {
        when(acl.hasGrant(AclResourceType.MESSAGE_BASE, 7L, AclPermission.POST, AclPrincipalType.ROLE, 5L))
                .thenReturn(false);

        screen.onKey(ctx, "P");

        verify(acl).grant(AclResourceType.MESSAGE_BASE, 7L, AclPermission.VIEW, AclPrincipalType.ROLE, 5L);
        verify(acl).grant(AclResourceType.MESSAGE_BASE, 7L, AclPermission.POST, AclPrincipalType.ROLE, 5L);
    }
}
