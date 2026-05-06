package io.aeyer.voidcore.messages;

import org.jooq.DSLContext;

import java.util.List;
import java.util.Optional;

import static io.aeyer.voidcore.jooq.Tables.MESSAGE_BASES;
import static io.aeyer.voidcore.jooq.Tables.THREADS;
import static io.aeyer.voidcore.jooq.Tables.THREAD_READ;

/**
 * jOOQ-backed read of the message_bases table per SPEC §3 / §7.2.
 * v1 ships 4 seeded bases; sysop CRUD is a future enhancement (#36 is
 * scoped to the user-facing list).
 */
public class MessageBaseRepository {

    public record BaseWithUnread(MessageBase base, int unread) {}

    private final DSLContext dsl;

    public MessageBaseRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public List<MessageBase> listAll() {
        return dsl.select(MESSAGE_BASES.ID, MESSAGE_BASES.SLUG, MESSAGE_BASES.NAME,
                        MESSAGE_BASES.DESCRIPTION, MESSAGE_BASES.SORT_ORDER, MESSAGE_BASES.IS_LOCKED)
                .from(MESSAGE_BASES)
                .orderBy(MESSAGE_BASES.SORT_ORDER, MESSAGE_BASES.ID)
                .fetch(r -> new MessageBase(
                        r.get(MESSAGE_BASES.ID),
                        r.get(MESSAGE_BASES.SLUG),
                        r.get(MESSAGE_BASES.NAME),
                        r.get(MESSAGE_BASES.DESCRIPTION),
                        r.get(MESSAGE_BASES.SORT_ORDER),
                        r.get(MESSAGE_BASES.IS_LOCKED)));
    }

    public Optional<MessageBase> findById(long id) {
        return dsl.select(MESSAGE_BASES.ID, MESSAGE_BASES.SLUG, MESSAGE_BASES.NAME,
                        MESSAGE_BASES.DESCRIPTION, MESSAGE_BASES.SORT_ORDER, MESSAGE_BASES.IS_LOCKED)
                .from(MESSAGE_BASES)
                .where(MESSAGE_BASES.ID.eq(id))
                .fetchOptional()
                .map(r -> new MessageBase(
                        r.get(MESSAGE_BASES.ID),
                        r.get(MESSAGE_BASES.SLUG),
                        r.get(MESSAGE_BASES.NAME),
                        r.get(MESSAGE_BASES.DESCRIPTION),
                        r.get(MESSAGE_BASES.SORT_ORDER),
                        r.get(MESSAGE_BASES.IS_LOCKED)));
    }

    /**
     * Per-base unread count for a given user. A thread is "unread" if its
     * last_post_at is later than the user's thread_read.last_read_at, or if
     * no thread_read row exists for that thread yet.
     */
    public List<BaseWithUnread> listAllWithUnread(long userId) {
        var bases = listAll();
        java.util.ArrayList<BaseWithUnread> out = new java.util.ArrayList<>(bases.size());
        for (var b : bases) {
            Integer unread = dsl.selectCount()
                    .from(THREADS)
                    .leftJoin(THREAD_READ).on(THREAD_READ.THREAD_ID.eq(THREADS.ID)
                            .and(THREAD_READ.USER_ID.eq(userId)))
                    .where(THREADS.BASE_ID.eq(b.id()))
                    .and(THREAD_READ.LAST_READ_AT.isNull()
                            .or(THREADS.LAST_POST_AT.gt(THREAD_READ.LAST_READ_AT)))
                    .fetchOne(0, Integer.class);
            out.add(new BaseWithUnread(b, unread == null ? 0 : unread));
        }
        return out;
    }
}
