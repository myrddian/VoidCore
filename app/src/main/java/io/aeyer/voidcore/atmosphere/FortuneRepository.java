package io.aeyer.voidcore.atmosphere;

import org.jooq.DSLContext;

import java.util.Optional;

import static io.aeyer.voidcore.jooq.Tables.FORTUNES;

/**
 * Tiny single-line snippets surfaced in the BBS atmosphere ticket
 * (#93). Pulled at random from {@code fortunes} to add flavour to
 * surfaces like the goodbye screen and the login summary footer.
 *
 * <p>{@code TABLESAMPLE} would be the textbook randomiser, but at
 * v1 scale (a dozen rows) {@code ORDER BY random() LIMIT 1} is
 * fine — Postgres doesn't even index it.
 */
public class FortuneRepository {

    private final DSLContext dsl;

    public FortuneRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** Random fortune. Empty if the table is empty. */
    public Optional<String> random() {
        try {
            String text = dsl.select(FORTUNES.TEXT)
                    .from(FORTUNES)
                    .orderBy(org.jooq.impl.DSL.field("random()"))
                    .limit(1)
                    .fetchOne(FORTUNES.TEXT);
            return Optional.ofNullable(text);
        } catch (RuntimeException e) {
            // Defensive — in DB-less profiles this bean still lives,
            // but the table might not exist yet. Fortunes are flavour;
            // their absence shouldn't disrupt the screens that use them.
            return Optional.empty();
        }
    }
}
