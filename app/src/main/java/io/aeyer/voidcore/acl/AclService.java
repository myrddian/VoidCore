package io.aeyer.voidcore.acl;

import io.aeyer.voidcore.auth.RoleRepository;
import io.aeyer.voidcore.ws.VoidCoreSession;

import java.util.List;

public class AclService {

    private final AclRepository repo;
    private final RoleRepository roles;

    public AclService(AclRepository repo, RoleRepository roles) {
        this.repo = repo;
        this.roles = roles;
    }

    public void grant(AclResourceType resourceType,
                      long resourceId,
                      AclPermission permission,
                      AclPrincipalType principalType,
                      Long principalId) {
        repo.grant(resourceType, resourceId, permission, principalType, principalId);
    }

    public void grantRoleIfPresent(AclResourceType resourceType,
                                   long resourceId,
                                   AclPermission permission,
                                   String roleName) {
        roles.findByName(roleName)
                .ifPresent(role -> repo.grant(resourceType, resourceId, permission,
                        AclPrincipalType.ROLE, role.id()));
    }

    public boolean can(VoidCoreSession session,
                       AclResourceType resourceType,
                       long resourceId,
                       AclPermission permission) {
        Long userId = session.userId();
        List<Long> roleIds = userId == null ? List.of() : roles.roleIdsForUser(userId);
        return repo.allows(resourceType, resourceId, permission, userId, session.isSysop(), roleIds);
    }

    public boolean canUser(long userId,
                           boolean isSysop,
                           AclResourceType resourceType,
                           long resourceId,
                           AclPermission permission) {
        List<Long> roleIds = roles.roleIdsForUser(userId);
        return repo.allows(resourceType, resourceId, permission, userId, isSysop, roleIds);
    }

    public boolean hasAnyManageAccess(VoidCoreSession session) {
        Long userId = session.userId();
        List<Long> roleIds = userId == null ? List.of() : roles.roleIdsForUser(userId);
        return repo.hasAnyManageAccess(userId, session.isSysop(), roleIds);
    }
}
