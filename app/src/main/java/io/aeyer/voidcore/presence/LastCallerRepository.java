package io.aeyer.voidcore.presence;

import org.jooq.DSLContext;

import java.util.List;

import static io.aeyer.voidcore.jooq.Tables.LAST_CALLERS;
import static io.aeyer.voidcore.jooq.Tables.USERS;

/**
 * jOOQ-backed append + capped read of the {@code last_callers} denormalised
 * table per SPEC §3 / §10 Phase 1 ("last callers"). ADR-005a.
 */
public class LastCallerRepository {

    private final DSLContext dsl;

    public LastCallerRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public void record(long userId) {
        dsl.insertInto(LAST_CALLERS).set(LAST_CALLERS.USER_ID, userId).execute();
    }

    public List<LastCaller> recent(int limit) {
        return dsl.select(USERS.HANDLE, USERS.LOCATION, LAST_CALLERS.AT)
                .from(LAST_CALLERS)
                .join(USERS).on(USERS.ID.eq(LAST_CALLERS.USER_ID))
                .orderBy(LAST_CALLERS.AT.desc())
                .limit(limit)
                .fetch(r -> new LastCaller(
                        r.get(USERS.HANDLE),
                        r.get(USERS.LOCATION),
                        r.get(LAST_CALLERS.AT)));
    }
}
