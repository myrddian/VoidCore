package io.aeyer.voidcore.oneliners;

import org.jooq.DSLContext;

import java.time.OffsetDateTime;
import java.util.List;

import static io.aeyer.voidcore.jooq.Tables.ONELINERS;
import static io.aeyer.voidcore.jooq.Tables.USERS;

/**
 * jOOQ-backed CRUD for the {@code oneliners} table per SPEC §3 / §7.4. The
 * 70-char body limit is enforced by a CHECK constraint at the schema layer
 * (V1__initial_schema.sql) so the insert path can rely on the database to
 * reject long input — caller validates locally first to give a friendlier
 * error before the round-trip.
 */
public class OnelinerRepository {

    private final DSLContext dsl;

    public OnelinerRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public long insert(long authorId, String body) {
        Long id = dsl.insertInto(ONELINERS)
                .set(ONELINERS.AUTHOR_ID, authorId)
                .set(ONELINERS.BODY, body)
                .returningResult(ONELINERS.ID)
                .fetchOne(ONELINERS.ID);
        if (id == null) throw new IllegalStateException("INSERT … RETURNING produced no id");
        return id;
    }

    /**
     * Count of oneliners posted strictly after {@code since}. Null
     * cutoff returns the full count (used for fresh registers).
     * Ticket #85 login summary.
     */
    public long countSince(OffsetDateTime since) {
        var step = dsl.selectCount().from(ONELINERS);
        if (since == null) return step.fetchOne(0, Long.class);
        return step.where(ONELINERS.POSTED_AT.gt(since))
                .fetchOne(0, Long.class);
    }

    /** Most-recent {@code limit} oneliners with handle, newest first. */
    public List<Oneliner> recent(int limit) {
        return dsl.select(ONELINERS.ID, USERS.HANDLE, ONELINERS.BODY, ONELINERS.POSTED_AT)
                .from(ONELINERS)
                .join(USERS).on(USERS.ID.eq(ONELINERS.AUTHOR_ID))
                .orderBy(ONELINERS.POSTED_AT.desc())
                .limit(limit)
                .fetch(r -> new Oneliner(
                        r.get(ONELINERS.ID),
                        r.get(USERS.HANDLE),
                        r.get(ONELINERS.BODY),
                        r.get(ONELINERS.POSTED_AT)));
    }
}
