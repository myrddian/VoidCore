package io.aeyer.voidcore.extensions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Record1;
import org.jooq.Table;
import org.jooq.impl.DSL;

import java.util.List;
import java.util.Optional;

/**
 * JSONB-backed state store for extension-owned data.
 */
public class ExtensionDataRepository {

    private static final Table<?> EXTENSIONS_DATA = DSL.table("extensions_data");
    private static final Field<String> EXTENSION_SLUG = DSL.field("extension_slug", String.class);
    private static final Field<String> SCOPE_TYPE = DSL.field("scope_type", String.class);
    private static final Field<String> SCOPE_KEY = DSL.field("scope_key", String.class);
    private static final Field<String> KEY = DSL.field("key", String.class);
    private static final Field<JSONB> VALUE = DSL.field("value", JSONB.class);

    private final DSLContext dsl;
    private final ObjectMapper json;

    public ExtensionDataRepository(DSLContext dsl, ObjectMapper json) {
        this.dsl = dsl;
        this.json = json;
    }

    public Optional<JsonNode> get(String extensionSlug, String scopeType, String scopeKey, String key) {
        Record1<JSONB> row = dsl.select(VALUE)
                .from(EXTENSIONS_DATA)
                .where(EXTENSION_SLUG.eq(extensionSlug))
                .and(SCOPE_TYPE.eq(scopeType))
                .and(SCOPE_KEY.eq(scopeKey))
                .and(KEY.eq(key))
                .fetchOne();
        if (row == null) return Optional.empty();
        return Optional.ofNullable(parse(row.get(VALUE)));
    }

    public void put(String extensionSlug, String scopeType, String scopeKey, String key, JsonNode value) {
        String payload = toJson(value);
        int updated = dsl.update(EXTENSIONS_DATA)
                .set(VALUE, JSONB.valueOf(payload))
                .set(DSL.field("updated_at"), DSL.currentOffsetDateTime())
                .where(EXTENSION_SLUG.eq(extensionSlug))
                .and(SCOPE_TYPE.eq(scopeType))
                .and(SCOPE_KEY.eq(scopeKey))
                .and(KEY.eq(key))
                .execute();
        if (updated > 0) {
            return;
        }
        dsl.insertInto(EXTENSIONS_DATA)
                .columns(EXTENSION_SLUG, SCOPE_TYPE, SCOPE_KEY, KEY, VALUE)
                .values(extensionSlug, scopeType, scopeKey, key, JSONB.valueOf(payload))
                .execute();
    }

    public void delete(String extensionSlug, String scopeType, String scopeKey, String key) {
        dsl.deleteFrom(EXTENSIONS_DATA)
                .where(EXTENSION_SLUG.eq(extensionSlug))
                .and(SCOPE_TYPE.eq(scopeType))
                .and(SCOPE_KEY.eq(scopeKey))
                .and(KEY.eq(key))
                .execute();
    }

    public List<String> keys(String extensionSlug, String scopeType, String scopeKey, String prefix, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        String safePrefix = prefix == null ? "" : prefix;
        return dsl.select(KEY)
                .from(EXTENSIONS_DATA)
                .where(EXTENSION_SLUG.eq(extensionSlug))
                .and(SCOPE_TYPE.eq(scopeType))
                .and(SCOPE_KEY.eq(scopeKey))
                .and(KEY.like(safePrefix + "%"))
                .orderBy(KEY.asc())
                .limit(safeLimit)
                .fetch(KEY);
    }

    private JsonNode parse(JSONB value) {
        if (value == null) return null;
        try {
            return json.readTree(value.data());
        } catch (Exception e) {
            return json.nullNode();
        }
    }

    private String toJson(JsonNode value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            return value == null ? "null" : value.toString();
        }
    }
}
