package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.acl.AclPermission;
import io.aeyer.voidcore.acl.AclPrincipalType;
import io.aeyer.voidcore.acl.AclRepository;
import io.aeyer.voidcore.acl.AclResourceType;
import io.aeyer.voidcore.ws.VoidCoreSession;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.BbsServices;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SysopRoleOnelinersScreenTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    AclRepository acl;
    BbsContext ctx;
    BbsServices services;
    VoidCoreSession session;
    SysopRoleOnelinersScreen screen;

    @BeforeEach
    void setUp() {
        acl = mock(AclRepository.class);
        ctx = mock(BbsContext.class);
        services = mock(BbsServices.class);
        session = mock(VoidCoreSession.class);

        when(ctx.isSysop()).thenReturn(true);
        when(ctx.session()).thenReturn(session);
        when(ctx.services()).thenReturn(services);
        when(services.json()).thenReturn(JSON);
        when(session.selectedSysopId()).thenReturn(5L);

        screen = new SysopRoleOnelinersScreen(acl);
    }

    @Test
    void V_grants_view_when_missing() {
        when(acl.hasGrant(AclResourceType.ONELINER_WALL, OnelinersScreen.WALL_ID,
                AclPermission.VIEW, AclPrincipalType.ROLE, 5L)).thenReturn(false);

        screen.onKey(ctx, "V");

        verify(acl).grant(AclResourceType.ONELINER_WALL, OnelinersScreen.WALL_ID,
                AclPermission.VIEW, AclPrincipalType.ROLE, 5L);
        verify(ctx).publish(OnelinersScreen.TOPIC);
    }

    @Test
    void P_grants_post_and_view_when_missing() {
        when(acl.hasGrant(AclResourceType.ONELINER_WALL, OnelinersScreen.WALL_ID,
                AclPermission.POST, AclPrincipalType.ROLE, 5L)).thenReturn(false);

        screen.onKey(ctx, "P");

        verify(acl).grant(AclResourceType.ONELINER_WALL, OnelinersScreen.WALL_ID,
                AclPermission.VIEW, AclPrincipalType.ROLE, 5L);
        verify(acl).grant(AclResourceType.ONELINER_WALL, OnelinersScreen.WALL_ID,
                AclPermission.POST, AclPrincipalType.ROLE, 5L);
    }
}
