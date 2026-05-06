package io.aeyer.voidcore.social;

import org.jooq.DSLContext;

import java.time.OffsetDateTime;
import java.util.List;

import static io.aeyer.voidcore.jooq.Tables.USERS;
import static io.aeyer.voidcore.jooq.Tables.WATCH_LIST;

/**
 * #91 watch list repo. Each row binds a watcher to a watched user;
 * the schema's {@code CHECK (watcher_id <> watched_id)} prevents
 * self-watches at the DB level.
 */
public class WatchListRepository {

    public record WatchedUser(long userId, String handle, OffsetDateTime watchedAt) {}

    private final DSLContext dsl;

    public WatchListRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** Add the relationship; idempotent via {@code ON CONFLICT DO NOTHING}. */
    public void watch(long watcherId, long watchedId) {
        if (watcherId == watchedId) return;            // belt+suspenders
        dsl.insertInto(WATCH_LIST)
                .set(WATCH_LIST.WATCHER_ID, watcherId)
                .set(WATCH_LIST.WATCHED_ID, watchedId)
                .onConflictDoNothing()
                .execute();
    }

    public void unwatch(long watcherId, long watchedId) {
        dsl.deleteFrom(WATCH_LIST)
                .where(WATCH_LIST.WATCHER_ID.eq(watcherId))
                .and(WATCH_LIST.WATCHED_ID.eq(watchedId))
                .execute();
    }

    public boolean isWatching(long watcherId, long watchedId) {
        return dsl.fetchExists(
                dsl.selectOne()
                        .from(WATCH_LIST)
                        .where(WATCH_LIST.WATCHER_ID.eq(watcherId))
                        .and(WATCH_LIST.WATCHED_ID.eq(watchedId)));
    }

    /** List of users that {@code watcherId} is watching, joined to handles. */
    public List<WatchedUser> watchedBy(long watcherId) {
        return dsl.select(USERS.ID, USERS.HANDLE, WATCH_LIST.WATCHED_AT)
                .from(WATCH_LIST)
                .join(USERS).on(USERS.ID.eq(WATCH_LIST.WATCHED_ID))
                .where(WATCH_LIST.WATCHER_ID.eq(watcherId))
                .orderBy(WATCH_LIST.WATCHED_AT.desc())
                .fetch(r -> new WatchedUser(
                        r.get(USERS.ID),
                        r.get(USERS.HANDLE),
                        r.get(WATCH_LIST.WATCHED_AT)));
    }
}
