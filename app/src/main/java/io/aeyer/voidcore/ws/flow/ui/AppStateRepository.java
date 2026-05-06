package io.aeyer.voidcore.ws.flow.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jooq.DSLContext;
import org.jooq.JSONB;

import java.util.Optional;

import static io.aeyer.voidcore.jooq.Tables.SESSIONS;

/**
 * Read/write the per-session, per-app scratchpad column
 * {@code sessions.app_state}. Keys are app-defined strings (e.g.
 * {@code "doc:42"}); values are JSON objects scoped to that key.
 *
 * <p>Implementation note: the column is loaded as a whole, mutated
 * in Java, then written back via typed jOOQ + {@link JSONB#valueOf}
 * — same pattern as {@code UserRepository.setPreferences} and
 * {@code SessionRepository.updateScreen}. Concurrent partial writes
 * to disjoint sub-keys are not protected at the DB level, but in
 * practice only one writer (the session's WS owner) ever touches
 * a given row's {@code app_state} so the read-modify-write window
 * is uncontested.
 */
public class AppStateRepository {

    private final DSLContext dsl;
    private final ObjectMapper json;

    public AppStateRepository(DSLContext dsl, ObjectMapper json) {
        this.dsl = dsl;
        this.json = json;
    }

    public Optional<ObjectNode> read(String sessionToken, String appKey) {
        ObjectNode all = loadAll(sessionToken).orElse(null);
        if (all == null) return Optional.empty();
        JsonNode sub = all.get(appKey);
        if (sub == null || !sub.isObject()) return Optional.empty();
        return Optional.of((ObjectNode) sub);
    }

    public void write(String sessionToken, String appKey, ObjectNode payload) {
        ObjectNode all = loadAll(sessionToken).orElseGet(json::createObjectNode);
        all.set(appKey, payload);
        persist(sessionToken, all);
    }

    public void wipe(String sessionToken, String appKey) {
        ObjectNode all = loadAll(sessionToken).orElse(null);
        if (all == null) return;
        if (!all.has(appKey)) return;
        all.remove(appKey);
        persist(sessionToken, all);
    }

    private Optional<ObjectNode> loadAll(String sessionToken) {
        JSONB raw = dsl.select(SESSIONS.APP_STATE)
                .from(SESSIONS)
                .where(SESSIONS.TOKEN.eq(sessionToken))
                .fetchOne(SESSIONS.APP_STATE);
        if (raw == null) return Optional.empty();
        try {
            JsonNode parsed = json.readTree(raw.data());
            if (parsed instanceof ObjectNode obj) return Optional.of(obj);
            return Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private void persist(String sessionToken, ObjectNode all) {
        try {
            String text = json.writeValueAsString(all);
            dsl.update(SESSIONS)
               .set(SESSIONS.APP_STATE, JSONB.valueOf(text))
               .where(SESSIONS.TOKEN.eq(sessionToken))
               .execute();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // ObjectNode.toString() is the canonical fallback when
            // writeValueAsString fails — but for ObjectNode this should
            // never throw. Log + skip rather than corrupt the column.
            org.slf4j.LoggerFactory.getLogger(AppStateRepository.class)
                    .warn("app_state persist failed for {}: {}", sessionToken, e.toString());
        }
    }
}
