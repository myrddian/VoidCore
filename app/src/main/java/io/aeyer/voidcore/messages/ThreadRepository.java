package io.aeyer.voidcore.messages;

import org.jooq.DSLContext;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static io.aeyer.voidcore.jooq.Tables.THREADS;
import static io.aeyer.voidcore.jooq.Tables.THREAD_READ;
import static io.aeyer.voidcore.jooq.Tables.USERS;

/**
 * Threads + thread_read access per SPEC §3 / §7.2 / §38 (unread tracking).
 * Read state is upserted on entry to the thread view.
 */
public class ThreadRepository {

    public record ThreadWithUnread(BoardThread thread, boolean unread) {}

    private final DSLContext dsl;

    public ThreadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public long insert(long baseId, String subject, long authorId) {
        Long id = dsl.insertInto(THREADS)
                .set(THREADS.BASE_ID, baseId)
                .set(THREADS.SUBJECT, subject)
                .set(THREADS.AUTHOR_ID, authorId)
                .returningResult(THREADS.ID)
                .fetchOne(THREADS.ID);
        if (id == null) throw new IllegalStateException("INSERT … RETURNING produced no id");
        return id;
    }

    /**
     * Count of threads created strictly after {@code since}. Null
     * cutoff counts everything. Used by the login-summary screen
     * (ticket #85).
     */
    public long countSince(OffsetDateTime since) {
        var step = dsl.selectCount().from(THREADS);
        if (since == null) return step.fetchOne(0, Long.class);
        return step.where(THREADS.CREATED_AT.gt(since))
                .fetchOne(0, Long.class);
    }

    public Optional<BoardThread> findById(long id) {
        return dsl.select(THREADS.ID, THREADS.BASE_ID, THREADS.SUBJECT, USERS.HANDLE,
                        THREADS.CREATED_AT, THREADS.LAST_POST_AT, THREADS.POST_COUNT,
                        THREADS.IS_PINNED, THREADS.IS_LOCKED)
                .from(THREADS)
                .join(USERS).on(USERS.ID.eq(THREADS.AUTHOR_ID))
                .where(THREADS.ID.eq(id))
                .fetchOptional()
                .map(this::toDomain);
    }

    public List<ThreadWithUnread> listInBase(long baseId, long userId) {
        return dsl.select(THREADS.ID, THREADS.BASE_ID, THREADS.SUBJECT, USERS.HANDLE,
                        THREADS.CREATED_AT, THREADS.LAST_POST_AT, THREADS.POST_COUNT,
                        THREADS.IS_PINNED, THREADS.IS_LOCKED, THREAD_READ.LAST_READ_AT)
                .from(THREADS)
                .join(USERS).on(USERS.ID.eq(THREADS.AUTHOR_ID))
                .leftJoin(THREAD_READ).on(THREAD_READ.THREAD_ID.eq(THREADS.ID)
                        .and(THREAD_READ.USER_ID.eq(userId)))
                .where(THREADS.BASE_ID.eq(baseId))
                .orderBy(THREADS.IS_PINNED.desc(), THREADS.LAST_POST_AT.desc())
                .limit(9)
                .fetch(r -> {
                    BoardThread t = new BoardThread(
                            r.get(THREADS.ID),
                            r.get(THREADS.BASE_ID),
                            r.get(THREADS.SUBJECT),
                            r.get(USERS.HANDLE),
                            r.get(THREADS.CREATED_AT),
                            r.get(THREADS.LAST_POST_AT),
                            r.get(THREADS.POST_COUNT),
                            r.get(THREADS.IS_PINNED),
                            r.get(THREADS.IS_LOCKED));
                    OffsetDateTime lastRead = r.get(THREAD_READ.LAST_READ_AT);
                    boolean unread = lastRead == null
                            || (t.lastPostAt() != null && t.lastPostAt().isAfter(lastRead));
                    return new ThreadWithUnread(t, unread);
                });
    }

    /** Upsert thread_read on entry, marking the thread as just-read. */
    public void markRead(long userId, long threadId) {
        dsl.insertInto(THREAD_READ)
                .set(THREAD_READ.USER_ID, userId)
                .set(THREAD_READ.THREAD_ID, threadId)
                .set(THREAD_READ.LAST_READ_AT, OffsetDateTime.now(ZoneOffset.UTC))
                .onConflict(THREAD_READ.USER_ID, THREAD_READ.THREAD_ID)
                .doUpdate()
                .set(THREAD_READ.LAST_READ_AT, OffsetDateTime.now(ZoneOffset.UTC))
                .execute();
    }

    /** Bumped on every post insert by PostRepository so the list view sorts right. */
    void bumpAfterPost(long threadId) {
        dsl.update(THREADS)
                .set(THREADS.LAST_POST_AT, OffsetDateTime.now(ZoneOffset.UTC))
                .set(THREADS.POST_COUNT, THREADS.POST_COUNT.plus(1))
                .where(THREADS.ID.eq(threadId))
                .execute();
    }

    private BoardThread toDomain(org.jooq.Record r) {
        return new BoardThread(
                r.get(THREADS.ID),
                r.get(THREADS.BASE_ID),
                r.get(THREADS.SUBJECT),
                r.get(USERS.HANDLE),
                r.get(THREADS.CREATED_AT),
                r.get(THREADS.LAST_POST_AT),
                r.get(THREADS.POST_COUNT),
                r.get(THREADS.IS_PINNED),
                r.get(THREADS.IS_LOCKED));
    }
}
