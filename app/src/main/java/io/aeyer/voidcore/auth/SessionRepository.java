package io.aeyer.voidcore.auth;

import io.aeyer.voidcore.repo.PgTypes;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Record;

import java.time.OffsetDateTime;
import java.util.Optional;

import static io.aeyer.voidcore.jooq.Tables.SESSIONS;
import static org.jooq.impl.DSL.currentOffsetDateTime;

/**
 * Persistence for the {@code sessions} table, typed against the jOOQ
 * codegen output (see {@link io.aeyer.voidcore.jooq.tables.Sessions}). Per
 * ADR-005a: parameter-bound by default via the jOOQ DSL, raw SQL only via
 * explicit opt-in.
 *
 * <p>"Active" rows are those where {@code expires_at > now()}. Expired rows
 * remain in the table until {@link #deleteExpired()} sweeps them; the read
 * paths filter them out so callers always see fresh data.
 */
public class SessionRepository {

    private final DSLContext dsl;
    private final ObjectMapper json;

    public SessionRepository(DSLContext dsl, ObjectMapper json) {
        this.dsl = dsl;
        this.json = json;
    }

    public void insert(String token, long userId, String ip, String userAgent,
                       OffsetDateTime expiresAt) {
        dsl.insertInto(SESSIONS)
                .set(SESSIONS.TOKEN, token)
                .set(SESSIONS.USER_ID, userId)
                .set(SESSIONS.IP, PgTypes.inet(ip))
                .set(SESSIONS.UA, userAgent)
                .set(SESSIONS.EXPIRES_AT, expiresAt)
                .execute();
    }

    public Optional<Session> touchAndExtend(String token, OffsetDateTime now, OffsetDateTime newExpiry) {
        var row = dsl.update(SESSIONS)
                .set(SESSIONS.LAST_SEEN_AT, now)
                .set(SESSIONS.EXPIRES_AT, newExpiry)
                .where(SESSIONS.TOKEN.eq(token))
                .and(SESSIONS.EXPIRES_AT.gt(now))
                .returning(SESSIONS.TOKEN, SESSIONS.USER_ID, SESSIONS.CREATED_AT,
                        SESSIONS.LAST_SEEN_AT, SESSIONS.EXPIRES_AT, SESSIONS.IP,
                        SESSIONS.UA, SESSIONS.CURRENT_SCREEN)
                .fetchOptional();
        return row.map(this::toDomain);
    }

    public Optional<Session> findActive(String token) {
        return findActive(token, null);
    }

    /**
     * Like {@link #findActive(String)} but uses {@code now} (instead of the
     * DB's wall clock) for the active-row predicate. Lets callers with an
     * injected {@code Clock} (e.g. tests, scheduled jobs) keep read and
     * write consistent — otherwise a session inserted with a clock-stubbed
     * {@code expires_at} can appear "instantly expired" if the stubbed
     * clock is in the past relative to wall time.
     *
     * <p>{@code now == null} reverts to the DB's wall clock.
     */
    public Optional<Session> findActive(String token, OffsetDateTime now) {
        var row = dsl.select(SESSIONS.TOKEN, SESSIONS.USER_ID, SESSIONS.CREATED_AT,
                        SESSIONS.LAST_SEEN_AT, SESSIONS.EXPIRES_AT, SESSIONS.IP,
                        SESSIONS.UA, SESSIONS.CURRENT_SCREEN)
                .from(SESSIONS)
                .where(SESSIONS.TOKEN.eq(token))
                .and(now == null
                        ? SESSIONS.EXPIRES_AT.gt(currentOffsetDateTime())
                        : SESSIONS.EXPIRES_AT.gt(now))
                .fetchOptional();
        return row.map(this::toDomain);
    }

    public boolean updateCurrentScreen(String token, JsonNode screen) {
        return dsl.update(SESSIONS)
                .set(SESSIONS.CURRENT_SCREEN, JSONB.valueOf(toJson(screen)))
                .where(SESSIONS.TOKEN.eq(token))
                .and(SESSIONS.EXPIRES_AT.gt(currentOffsetDateTime()))
                .execute() == 1;
    }

    public boolean delete(String token) {
        return dsl.deleteFrom(SESSIONS).where(SESSIONS.TOKEN.eq(token)).execute() == 1;
    }

    public int deleteExpired() {
        return dsl.deleteFrom(SESSIONS)
                .where(SESSIONS.EXPIRES_AT.le(currentOffsetDateTime()))
                .execute();
    }

    private Session toDomain(Record r) {
        JSONB jsonb = r.get(SESSIONS.CURRENT_SCREEN);
        JsonNode screen = jsonb == null ? null : parseJson(jsonb.data());
        return new Session(
                r.get(SESSIONS.TOKEN),
                r.get(SESSIONS.USER_ID),
                r.get(SESSIONS.CREATED_AT),
                r.get(SESSIONS.LAST_SEEN_AT),
                r.get(SESSIONS.EXPIRES_AT),
                r.get(SESSIONS.IP),
                r.get(SESSIONS.UA),
                screen);
    }

    private JsonNode parseJson(String s) {
        try {
            return json.readTree(s);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("malformed current_screen JSON", e);
        }
    }

    private String toJson(JsonNode node) {
        try {
            return json.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("could not serialise current_screen", e);
        }
    }
}
