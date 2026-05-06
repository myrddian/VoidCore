package io.aeyer.voidcore.auth;

import org.jooq.DSLContext;

import static io.aeyer.voidcore.jooq.Tables.COUNTERS;

/**
 * Reads/writes the site-wide {@code counters} table from SPEC §3, jOOQ DSL
 * backed (ADR-005a). The {@code caller_count} row is seeded at 1337 (V3
 * migration) and bumped on every successful auth so the welcome bulletin's
 * {@code {{call_no}}} placeholder shows the caller's number.
 */
public class CounterRepository {

    public static final String CALLER_COUNT = "caller_count";

    private final DSLContext dsl;

    public CounterRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** Atomic increment, returning the new value. */
    public long increment(String key) {
        Long val = dsl.insertInto(COUNTERS, COUNTERS.KEY, COUNTERS.VALUE)
                .values(key, 1L)
                .onConflict(COUNTERS.KEY)
                .doUpdate()
                .set(COUNTERS.VALUE, COUNTERS.VALUE.plus(1))
                .returningResult(COUNTERS.VALUE)
                .fetchOne(COUNTERS.VALUE);
        if (val == null) throw new IllegalStateException("RETURNING produced no value");
        return val;
    }

    public long get(String key) {
        Long val = dsl.select(COUNTERS.VALUE)
                .from(COUNTERS)
                .where(COUNTERS.KEY.eq(key))
                .fetchOne(COUNTERS.VALUE);
        return val == null ? 0L : val;
    }
}
