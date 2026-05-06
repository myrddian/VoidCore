package io.aeyer.voidcore.auth;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.jooq.impl.DSL;

import java.util.List;
import java.util.Optional;

public class RoleRepository {

    public record RoleRow(long id, String name, String description) {}

    private static final Table<?> ROLES = DSL.table(DSL.name("roles"));
    private static final Field<Long> ROLES_ID = DSL.field(DSL.name("roles", "id"), Long.class);
    private static final Field<String> ROLES_NAME = DSL.field(DSL.name("roles", "name"), String.class);
    private static final Field<String> ROLES_DESCRIPTION = DSL.field(DSL.name("roles", "description"), String.class);

    private static final Table<?> USER_ROLES = DSL.table(DSL.name("user_roles"));
    private static final Field<Long> USER_ROLES_USER_ID = DSL.field(DSL.name("user_roles", "user_id"), Long.class);
    private static final Field<Long> USER_ROLES_ROLE_ID = DSL.field(DSL.name("user_roles", "role_id"), Long.class);

    private final DSLContext dsl;

    public RoleRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public Optional<RoleRow> findByName(String name) {
        return dsl.select(ROLES_ID, ROLES_NAME, ROLES_DESCRIPTION)
                .from(ROLES)
                .where(ROLES_NAME.eq(name))
                .fetchOptional(r -> new RoleRow(
                        r.get(ROLES_ID),
                        r.get(ROLES_NAME),
                        r.get(ROLES_DESCRIPTION)));
    }

    public Optional<RoleRow> findById(long id) {
        return dsl.select(ROLES_ID, ROLES_NAME, ROLES_DESCRIPTION)
                .from(ROLES)
                .where(ROLES_ID.eq(id))
                .fetchOptional(r -> new RoleRow(
                        r.get(ROLES_ID),
                        r.get(ROLES_NAME),
                        r.get(ROLES_DESCRIPTION)));
    }

    public List<RoleRow> listAllRoles() {
        return dsl.select(ROLES_ID, ROLES_NAME, ROLES_DESCRIPTION)
                .from(ROLES)
                .orderBy(ROLES_NAME.asc())
                .fetch(r -> new RoleRow(
                        r.get(ROLES_ID),
                        r.get(ROLES_NAME),
                        r.get(ROLES_DESCRIPTION)));
    }

    public List<RoleRow> rolesForUser(long userId) {
        return dsl.select(ROLES_ID, ROLES_NAME, ROLES_DESCRIPTION)
                .from(ROLES)
                .join(USER_ROLES).on(USER_ROLES_ROLE_ID.eq(ROLES_ID))
                .where(USER_ROLES_USER_ID.eq(userId))
                .orderBy(ROLES_NAME.asc())
                .fetch(r -> new RoleRow(
                        r.get(ROLES_ID),
                        r.get(ROLES_NAME),
                        r.get(ROLES_DESCRIPTION)));
    }

    public List<Long> roleIdsForUser(long userId) {
        return dsl.select(USER_ROLES_ROLE_ID)
                .from(USER_ROLES)
                .where(USER_ROLES_USER_ID.eq(userId))
                .fetch(USER_ROLES_ROLE_ID);
    }

    public void assignRole(long userId, long roleId) {
        dsl.insertInto(USER_ROLES)
                .set(USER_ROLES_USER_ID, userId)
                .set(USER_ROLES_ROLE_ID, roleId)
                .onConflictDoNothing()
                .execute();
    }

    public void removeRole(long userId, long roleId) {
        dsl.deleteFrom(USER_ROLES)
                .where(USER_ROLES_USER_ID.eq(userId))
                .and(USER_ROLES_ROLE_ID.eq(roleId))
                .execute();
    }

    public long createRole(String name, String description) {
        Long id = dsl.insertInto(ROLES)
                .set(ROLES_NAME, name)
                .set(ROLES_DESCRIPTION, description)
                .returningResult(ROLES_ID)
                .fetchOne(ROLES_ID);
        if (id == null) throw new IllegalStateException("INSERT … RETURNING produced no role id");
        return id;
    }
}
