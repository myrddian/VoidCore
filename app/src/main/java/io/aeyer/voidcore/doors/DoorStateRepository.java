package io.aeyer.voidcore.doors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Record4;
import org.jooq.Table;
import org.jooq.impl.DSL;

import java.util.List;
import java.util.Optional;

public class DoorStateRepository {

    private static final Table<?> DOOR_STATE = DSL.table("door_state");
    private static final Field<String> DOOR_ID = DSL.field("door_id", String.class);
    private static final Field<String> SCOPE = DSL.field("scope", String.class);
    private static final Field<String> SCOPE_KEY = DSL.field("scope_key", String.class);
    private static final Field<String> KEY = DSL.field("key", String.class);
    private static final Field<JSONB> VALUE = DSL.field("value", JSONB.class);
    private static final Field<Long> VERSION = DSL.field("version", Long.class);

    private final DSLContext dsl;
    private final ObjectMapper json;

    public DoorStateRepository(DSLContext dsl, ObjectMapper json) {
        this.dsl = dsl;
        this.json = json;
    }

    public Optional<Entry> get(String doorId, String scope, String scopeKey, String key) {
        Record4<JSONB, Long, String, String> row = dsl.select(VALUE, VERSION, SCOPE, KEY)
                .from(DOOR_STATE)
                .where(DOOR_ID.eq(doorId))
                .and(SCOPE.eq(scope))
                .and(SCOPE_KEY.eq(scopeKey))
                .and(KEY.eq(key))
                .fetchOne();
        if (row == null) return Optional.empty();
        return Optional.of(new Entry(
                row.get(SCOPE),
                row.get(KEY),
                parse(row.get(VALUE)),
                row.get(VERSION)));
    }

    public PutResult put(String doorId,
                         String scope,
                         String scopeKey,
                         String key,
                         JsonNode value,
                         Long expectedVersion) {
        Optional<Entry> existing = get(doorId, scope, scopeKey, key);
        if (expectedVersion != null) {
            if (existing.isEmpty()) return PutResult.conflict(null);
            if (!expectedVersion.equals(existing.get().version())) {
                return PutResult.conflict(existing.get().version());
            }
        }

        if (existing.isPresent()) {
            long nextVersion = existing.get().version() + 1;
            dsl.update(DOOR_STATE)
                    .set(VALUE, JSONB.valueOf(toJson(value)))
                    .set(VERSION, nextVersion)
                    .set(DSL.field("updated_at"), DSL.currentOffsetDateTime())
                    .where(DOOR_ID.eq(doorId))
                    .and(SCOPE.eq(scope))
                    .and(SCOPE_KEY.eq(scopeKey))
                    .and(KEY.eq(key))
                    .execute();
            return PutResult.ok(nextVersion);
        }

        dsl.insertInto(DOOR_STATE)
                .columns(DOOR_ID, SCOPE, SCOPE_KEY, KEY, VALUE, VERSION)
                .values(doorId, scope, scopeKey, key, JSONB.valueOf(toJson(value)), 1L)
                .execute();
        return PutResult.ok(1L);
    }

    public void delete(String doorId, String scope, String scopeKey, String key) {
        dsl.deleteFrom(DOOR_STATE)
                .where(DOOR_ID.eq(doorId))
                .and(SCOPE.eq(scope))
                .and(SCOPE_KEY.eq(scopeKey))
                .and(KEY.eq(key))
                .execute();
    }

    public ScanPage scan(String doorId,
                         String scope,
                         String scopeKey,
                         String prefix,
                         String cursor,
                         int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        var query = dsl.select(KEY, VALUE, VERSION)
                .from(DOOR_STATE)
                .where(DOOR_ID.eq(doorId))
                .and(SCOPE.eq(scope))
                .and(SCOPE_KEY.eq(scopeKey))
                .and(KEY.like(prefix + "%"));
        if (cursor != null && !cursor.isBlank()) {
            query = query.and(KEY.gt(cursor));
        }
        List<Entry> entries = query.orderBy(KEY.asc())
                .limit(safeLimit + 1)
                .fetch(record -> new Entry(
                        scope,
                        record.get(KEY),
                        parse(record.get(VALUE)),
                        record.get(VERSION)));
        String nextCursor = null;
        if (entries.size() > safeLimit) {
            Entry overflow = entries.remove(entries.size() - 1);
            nextCursor = overflow.key();
        }
        return new ScanPage(entries, nextCursor);
    }

    private JsonNode parse(JSONB value) {
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

    public record Entry(String scope, String key, JsonNode value, Long version) {}

    public record ScanPage(List<Entry> entries, String cursor) {}

    public record PutResult(boolean ok, Long version, Long currentVersion) {
        static PutResult ok(Long version) {
            return new PutResult(true, version, null);
        }

        static PutResult conflict(Long currentVersion) {
            return new PutResult(false, null, currentVersion);
        }
    }
}
