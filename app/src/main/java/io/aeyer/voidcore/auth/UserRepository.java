package io.aeyer.voidcore.auth;

import org.jooq.DSLContext;
import org.jooq.exception.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static io.aeyer.voidcore.jooq.Tables.USERS;

/**
 * Reads/writes the {@code users} table via jOOQ DSL (ADR-005a). CITEXT
 * handles the case-insensitive uniqueness for {@code handle}; a SPEC §3
 * CHECK constraint enforces the format regex. Both surface as
 * {@code DataIntegrityViolationException} on violation and the AuthService
 * pattern-matches to friendly error codes.
 */
public class UserRepository {

    public record UserRow(long id, String handle, String pwHash, boolean isSysop, boolean isBanned) {}

    public record UserSummary(
            long id,
            String handle,
            String location,
            int callCount,
            int postCount,
            boolean isSysop,
            OffsetDateTime joinedAt,
            OffsetDateTime lastCallAt
    ) {}

    private final DSLContext dsl;

    public UserRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public Optional<UserRow> findByHandle(String handle) {
        return dsl.select(USERS.ID, USERS.HANDLE, USERS.PW_HASH, USERS.IS_SYSOP, USERS.IS_BANNED)
                .from(USERS)
                .where(USERS.HANDLE.eq(handle))
                .fetchOptional()
                .map(r -> new UserRow(
                        r.get(USERS.ID),
                        r.get(USERS.HANDLE),
                        r.get(USERS.PW_HASH),
                        r.get(USERS.IS_SYSOP),
                        r.get(USERS.IS_BANNED)));
    }

    public Optional<UserRow> findById(long id) {
        return dsl.select(USERS.ID, USERS.HANDLE, USERS.PW_HASH, USERS.IS_SYSOP, USERS.IS_BANNED)
                .from(USERS)
                .where(USERS.ID.eq(id))
                .fetchOptional()
                .map(r -> new UserRow(
                        r.get(USERS.ID),
                        r.get(USERS.HANDLE),
                        r.get(USERS.PW_HASH),
                        r.get(USERS.IS_SYSOP),
                        r.get(USERS.IS_BANNED)));
    }

    public List<UserSummary> listForBoard(int limit) {
        return dsl.select(USERS.ID, USERS.HANDLE, USERS.LOCATION, USERS.CALL_COUNT,
                        USERS.POST_COUNT, USERS.IS_SYSOP, USERS.JOINED_AT, USERS.LAST_CALL_AT)
                .from(USERS)
                .where(USERS.IS_BANNED.eq(false))
                .orderBy(USERS.HANDLE)
                .limit(limit)
                .fetch(r -> new UserSummary(
                        r.get(USERS.ID),
                        r.get(USERS.HANDLE),
                        r.get(USERS.LOCATION),
                        r.get(USERS.CALL_COUNT),
                        r.get(USERS.POST_COUNT),
                        r.get(USERS.IS_SYSOP),
                        r.get(USERS.JOINED_AT),
                        r.get(USERS.LAST_CALL_AT)));
    }

    public long insert(String handle, String pwHash, String location, String setup,
                       String foundVia, String favGenres) {
        try {
            Long id = dsl.insertInto(USERS)
                    .set(USERS.HANDLE, handle)
                    .set(USERS.PW_HASH, pwHash)
                    .set(USERS.LOCATION, location)
                    .set(USERS.SETUP, setup)
                    .set(USERS.FOUND_VIA, foundVia)
                    .set(USERS.FAV_GENRES, favGenres)
                    .returningResult(USERS.ID)
                    .fetchOne(USERS.ID);
            if (id == null) throw new IllegalStateException("INSERT … RETURNING produced no id");
            return id;
        } catch (DataAccessException e) {
            // Surface schema-level violations (CITEXT unique, handle CHECK)
            // as Spring's DataIntegrityViolationException so AuthService's
            // existing pattern-match still works.
            Throwable cause = e.getCause();
            if (cause instanceof SQLException sql) {
                throw new DataIntegrityViolationException(sql.getMessage(), sql);
            }
            throw e;
        }
    }

    public void markSysop(long userId) {
        dsl.update(USERS).set(USERS.IS_SYSOP, true).where(USERS.ID.eq(userId)).execute();
    }

    /**
     * Read {@code users.last_call_at} without mutating it. Used by
     * the login flow (ticket #85) to capture the prior value before
     * {@link #recordCall} bumps it, so the post-auth login-summary
     * screen can compute deltas against the right cutoff.
     */
    public Optional<OffsetDateTime> lastCallAt(long userId) {
        return Optional.ofNullable(
                dsl.select(USERS.LAST_CALL_AT)
                        .from(USERS).where(USERS.ID.eq(userId))
                        .fetchOne(USERS.LAST_CALL_AT));
    }

    /**
     * Read {@code users.call_count}. Used by the achievement
     * awarding pipeline to evaluate caller-10 / caller-100
     * milestones after {@link #recordCall} has bumped the value
     * during auth.login.
     */
    public Optional<Integer> callCount(long userId) {
        return Optional.ofNullable(
                dsl.select(USERS.CALL_COUNT)
                        .from(USERS).where(USERS.ID.eq(userId))
                        .fetchOne(USERS.CALL_COUNT));
    }

    public void recordCall(long userId) {
        dsl.update(USERS)
                .set(USERS.CALL_COUNT, USERS.CALL_COUNT.plus(1))
                .set(USERS.LAST_CALL_AT, OffsetDateTime.now(ZoneOffset.UTC))
                .where(USERS.ID.eq(userId))
                .execute();
    }

    public void updatePasswordHash(long userId, String newHash) {
        dsl.update(USERS).set(USERS.PW_HASH, newHash).where(USERS.ID.eq(userId)).execute();
    }

    /** Read users.preferences as a JSON string. Empty default on null. */
    public String preferences(long userId) {
        String s = dsl.select(USERS.PREFERENCES)
                .from(USERS).where(USERS.ID.eq(userId))
                .fetchOne(r -> {
                    org.jooq.JSONB p = r.value1();
                    return p == null ? null : p.data();
                });
        return s == null ? "{}" : s;
    }

    /** Replace users.preferences atomically. */
    public void setPreferences(long userId, String preferencesJson) {
        dsl.update(USERS)
                .set(USERS.PREFERENCES, org.jooq.JSONB.valueOf(preferencesJson))
                .where(USERS.ID.eq(userId))
                .execute();
    }

    /** Ban or unban; null reason on unban. */
    public void setBanned(long userId, boolean banned, String reason) {
        dsl.update(USERS)
                .set(USERS.IS_BANNED, banned)
                .set(USERS.BANNED_REASON, banned ? reason : null)
                .where(USERS.ID.eq(userId))
                .execute();
    }
}
