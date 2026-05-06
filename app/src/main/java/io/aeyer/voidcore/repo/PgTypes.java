package io.aeyer.voidcore.repo;

import org.jooq.Field;
import org.jooq.impl.DSL;

/**
 * Tiny helpers for Postgres types jOOQ's defaults don't fully cover. CITEXT
 * is satisfied by the forced-type config (codegen renders the column as
 * {@code Field<String>} and CITEXT does case-insensitive equality at the DB
 * layer), but INET is more awkward: forced-type-to-String makes the field
 * {@code Field<String>}, so reads come back as text — but on insert the
 * driver sends the bind as VARCHAR and Postgres refuses the implicit cast
 * to INET. Wrapping the value in {@code ?::inet} makes the cast explicit
 * at SQL-parse time, which Postgres accepts.
 *
 * <p>Per ADR-005a / SPEC §14, the path is "raw SQL only via explicit
 * opt-in"; this is exactly that opt-in, scoped to one Postgres-native cast.
 */
public final class PgTypes {

    private PgTypes() {}

    /** Bind a String into an INET column with an explicit {@code ?::inet} cast. */
    public static Field<String> inet(String value) {
        return DSL.field("{0}::inet", String.class, DSL.val(value));
    }
}
