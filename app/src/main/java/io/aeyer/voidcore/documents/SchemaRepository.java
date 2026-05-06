package io.aeyer.voidcore.documents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;

import java.util.List;
import java.util.Optional;

import static io.aeyer.voidcore.jooq.Tables.SCHEMAS;

/** Read-side adapter for the versioned schemas table introduced in V11. */
public class SchemaRepository {

    private final DSLContext dsl;
    private final ObjectMapper json;

    public SchemaRepository(DSLContext dsl, ObjectMapper json) {
        this.dsl = dsl;
        this.json = json;
    }

    public Optional<Schema> findActive(String slug) {
        if (slug == null || slug.isBlank()) return Optional.empty();
        return dsl.selectFrom(SCHEMAS)
                .where(SCHEMAS.SLUG.eq(slug))
                .and(SCHEMAS.STATUS.eq(SchemaStatus.ACTIVE.wireValue()))
                .orderBy(SCHEMAS.VERSION.desc())
                .limit(1)
                .fetchOptional(this::toRow);
    }

    public Optional<Schema> find(String slug, int version) {
        if (slug == null || slug.isBlank() || version < 1) return Optional.empty();
        return dsl.selectFrom(SCHEMAS)
                .where(SCHEMAS.SLUG.eq(slug))
                .and(SCHEMAS.VERSION.eq(version))
                .fetchOptional(this::toRow);
    }

    public List<Schema> listActive() {
        return dsl.selectFrom(SCHEMAS)
                .where(SCHEMAS.STATUS.eq(SchemaStatus.ACTIVE.wireValue()))
                .orderBy(SCHEMAS.SLUG.asc(), SCHEMAS.VERSION.desc())
                .fetch(this::toRow);
    }

    public List<Schema> listVersions(String slug) {
        if (slug == null || slug.isBlank()) return List.of();
        return dsl.selectFrom(SCHEMAS)
                .where(SCHEMAS.SLUG.eq(slug))
                .orderBy(SCHEMAS.VERSION.desc())
                .fetch(this::toRow);
    }

    private Schema toRow(Record r) {
        try {
            return new Schema(
                    r.get(SCHEMAS.ID),
                    r.get(SCHEMAS.SLUG),
                    r.get(SCHEMAS.VERSION),
                    r.get(SCHEMAS.LABEL),
                    r.get(SCHEMAS.DESCRIPTION),
                    readJson(r.get(SCHEMAS.DEFINITION)),
                    readJson(r.get(SCHEMAS.PRESENTATION)),
                    SchemaStatus.parse(r.get(SCHEMAS.STATUS)),
                    r.get(SCHEMAS.CREATED_BY),
                    r.get(SCHEMAS.CREATED_AT),
                    r.get(SCHEMAS.UPDATED_AT)
            );
        } catch (Exception e) {
            throw new IllegalStateException("Failed to project schema row: " + e.getMessage(), e);
        }
    }

    private JsonNode readJson(org.jooq.JSONB value) throws Exception {
        return json.readTree(value == null ? "{}" : value.data());
    }
}
