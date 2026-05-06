package io.aeyer.voidcore.acl;

import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.jooq.impl.DSL;

import java.util.Comparator;
import java.util.List;

/**
 * Generic ACL grant store keyed by resource type/id, principal, and permission.
 *
 * <p>This is intentionally system-level rather than chat-specific so the same
 * substrate can later back documents, releases, and other user-facing objects.
 */
public class AclRepository {

    public record GrantRow(AclResourceType resourceType, long resourceId, AclPermission permission) {}

    private static final Table<?> ACL_GRANTS = DSL.table(DSL.name("acl_grants"));
    private static final Field<String> RESOURCE_TYPE = DSL.field(DSL.name("acl_grants", "resource_type"), String.class);
    private static final Field<Long> RESOURCE_ID = DSL.field(DSL.name("acl_grants", "resource_id"), Long.class);
    private static final Field<String> PERMISSION = DSL.field(DSL.name("acl_grants", "permission"), String.class);
    private static final Field<String> PRINCIPAL_TYPE = DSL.field(DSL.name("acl_grants", "principal_type"), String.class);
    private static final Field<Long> PRINCIPAL_ID = DSL.field(DSL.name("acl_grants", "principal_id"), Long.class);

    private final DSLContext dsl;

    public AclRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public void grant(AclResourceType resourceType,
                      long resourceId,
                      AclPermission permission,
                      AclPrincipalType principalType,
                      Long principalId) {
        dsl.insertInto(ACL_GRANTS)
                .set(RESOURCE_TYPE, resourceType.wireValue())
                .set(RESOURCE_ID, resourceId)
                .set(PERMISSION, permission.wireValue())
                .set(PRINCIPAL_TYPE, principalType.wireValue())
                .set(PRINCIPAL_ID, principalId)
                .onConflictDoNothing()
                .execute();
    }

    public void revoke(AclResourceType resourceType,
                       long resourceId,
                       AclPermission permission,
                       AclPrincipalType principalType,
                       Long principalId) {
        Condition c = RESOURCE_TYPE.eq(resourceType.wireValue())
                .and(RESOURCE_ID.eq(resourceId))
                .and(PERMISSION.eq(permission.wireValue()))
                .and(PRINCIPAL_TYPE.eq(principalType.wireValue()));
        c = principalId == null ? c.and(PRINCIPAL_ID.isNull()) : c.and(PRINCIPAL_ID.eq(principalId));
        dsl.deleteFrom(ACL_GRANTS).where(c).execute();
    }

    public boolean hasGrant(AclResourceType resourceType,
                            long resourceId,
                            AclPermission permission,
                            AclPrincipalType principalType,
                            Long principalId) {
        Condition c = RESOURCE_TYPE.eq(resourceType.wireValue())
                .and(RESOURCE_ID.eq(resourceId))
                .and(PERMISSION.eq(permission.wireValue()))
                .and(PRINCIPAL_TYPE.eq(principalType.wireValue()));
        c = principalId == null ? c.and(PRINCIPAL_ID.isNull()) : c.and(PRINCIPAL_ID.eq(principalId));
        return dsl.fetchExists(DSL.selectOne().from(ACL_GRANTS).where(c));
    }

    public boolean allows(AclResourceType resourceType,
                          long resourceId,
                          AclPermission permission,
                          Long userId,
                          boolean isSysop,
                          List<Long> roleIds) {
        Condition principals = PRINCIPAL_TYPE.eq(AclPrincipalType.EVERYONE.wireValue());
        if (userId != null) {
            principals = principals
                    .or(PRINCIPAL_TYPE.eq(AclPrincipalType.AUTHENTICATED.wireValue()))
                    .or(PRINCIPAL_TYPE.eq(AclPrincipalType.USER.wireValue())
                            .and(PRINCIPAL_ID.eq(userId)));
        }
        if (roleIds != null && !roleIds.isEmpty()) {
            principals = principals.or(PRINCIPAL_TYPE.eq(AclPrincipalType.ROLE.wireValue())
                    .and(PRINCIPAL_ID.in(roleIds)));
        }
        if (isSysop) {
            principals = principals.or(PRINCIPAL_TYPE.eq(AclPrincipalType.SYSOP.wireValue()));
        }
        return dsl.fetchExists(
                DSL.selectOne()
                        .from(ACL_GRANTS)
                        .where(RESOURCE_TYPE.eq(resourceType.wireValue()))
                        .and(RESOURCE_ID.eq(resourceId))
                        .and(PERMISSION.in(permission.wireValue(), AclPermission.MANAGE.wireValue()))
                        .and(principals));
    }

    public boolean hasAnyManageAccess(Long userId, boolean isSysop, List<Long> roleIds) {
        if (isSysop) return true;
        Condition principals = PRINCIPAL_TYPE.eq(AclPrincipalType.EVERYONE.wireValue());
        if (userId != null) {
            principals = principals
                    .or(PRINCIPAL_TYPE.eq(AclPrincipalType.AUTHENTICATED.wireValue()))
                    .or(PRINCIPAL_TYPE.eq(AclPrincipalType.USER.wireValue())
                            .and(PRINCIPAL_ID.eq(userId)));
        }
        if (roleIds != null && !roleIds.isEmpty()) {
            principals = principals.or(PRINCIPAL_TYPE.eq(AclPrincipalType.ROLE.wireValue())
                    .and(PRINCIPAL_ID.in(roleIds)));
        }
        return dsl.fetchExists(
                DSL.selectOne()
                        .from(ACL_GRANTS)
                        .where(PERMISSION.eq(AclPermission.MANAGE.wireValue()))
                        .and(principals));
    }

    public List<GrantRow> listRoleGrants(long roleId) {
        return dsl.select(RESOURCE_TYPE, RESOURCE_ID, PERMISSION)
                .from(ACL_GRANTS)
                .where(PRINCIPAL_TYPE.eq(AclPrincipalType.ROLE.wireValue()))
                .and(PRINCIPAL_ID.eq(roleId))
                .fetch(r -> new GrantRow(
                        AclResourceType.valueOf(r.get(RESOURCE_TYPE).toUpperCase(java.util.Locale.ROOT)),
                        r.get(RESOURCE_ID),
                        AclPermission.valueOf(r.get(PERMISSION).toUpperCase(java.util.Locale.ROOT))))
                .stream()
                .sorted(Comparator
                        .comparing(GrantRow::resourceType)
                        .thenComparingLong(GrantRow::resourceId)
                        .thenComparing(GrantRow::permission))
                .toList();
    }
}
