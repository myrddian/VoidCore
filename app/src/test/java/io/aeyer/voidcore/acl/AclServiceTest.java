package io.aeyer.voidcore.acl;

import io.aeyer.voidcore.auth.RoleRepository;
import io.aeyer.voidcore.ws.VoidCoreSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AclServiceTest {

    AclRepository repo;
    RoleRepository roles;
    VoidCoreSession session;
    AclService service;

    @BeforeEach
    void setUp() {
        repo = mock(AclRepository.class);
        roles = mock(RoleRepository.class);
        session = mock(VoidCoreSession.class);

        when(session.userId()).thenReturn(7L);
        when(session.isSysop()).thenReturn(false);
        when(roles.roleIdsForUser(7L)).thenReturn(List.of(10L, 11L));

        service = new AclService(repo, roles);
    }

    @Test
    void can_resolves_user_role_ids_before_acl_check() {
        service.can(session, AclResourceType.CHAT_ROOM, 42L, AclPermission.VIEW);

        verify(repo).allows(AclResourceType.CHAT_ROOM, 42L, AclPermission.VIEW, 7L, false, List.of(10L, 11L));
    }

    @Test
    void grantRoleIfPresent_resolvesRoleIdBeforeGranting() {
        when(roles.findByName("ADMIN")).thenReturn(java.util.Optional.of(
                new RoleRepository.RoleRow(99L, "ADMIN", "Admin")));

        service.grantRoleIfPresent(AclResourceType.DOCUMENT, 5L, AclPermission.MANAGE, "ADMIN");

        verify(repo).grant(AclResourceType.DOCUMENT, 5L, AclPermission.MANAGE, AclPrincipalType.ROLE, 99L);
    }
}
