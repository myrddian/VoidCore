package io.aeyer.voidcore.netmail;

import org.jooq.DSLContext;
import org.jooq.impl.DSL;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static io.aeyer.voidcore.jooq.Tables.NETMAIL;
import static io.aeyer.voidcore.jooq.Tables.USERS;

/**
 * jOOQ-backed CRUD for the {@code netmail} table per SPEC §3 / §7.5. Soft-
 * delete via {@code from_deleted} / {@code to_deleted} so each side can
 * remove the message from their own view without affecting the other.
 */
public class NetmailRepository {

    private final DSLContext dsl;

    public NetmailRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public long insert(long fromId, long toId, String subject, String body) {
        Long id = dsl.insertInto(NETMAIL)
                .set(NETMAIL.FROM_ID, fromId)
                .set(NETMAIL.TO_ID, toId)
                .set(NETMAIL.SUBJECT, subject)
                .set(NETMAIL.BODY, body)
                .returningResult(NETMAIL.ID)
                .fetchOne(NETMAIL.ID);
        if (id == null) throw new IllegalStateException("INSERT … RETURNING produced no id");
        return id;
    }

    /** Inbox view: not-deleted-by-recipient, newest first. Capped to 9 for [1-9] selector. */
    public List<NetmailMessage> inbox(long userId) {
        var fromUsers = USERS.as("fu");
        var toUsers = USERS.as("tu");
        return dsl.select(NETMAIL.ID, NETMAIL.FROM_ID, NETMAIL.TO_ID,
                        fromUsers.HANDLE, toUsers.HANDLE,
                        NETMAIL.SUBJECT, NETMAIL.BODY,
                        NETMAIL.SENT_AT, NETMAIL.READ_AT)
                .from(NETMAIL)
                .join(fromUsers).on(fromUsers.ID.eq(NETMAIL.FROM_ID))
                .join(toUsers).on(toUsers.ID.eq(NETMAIL.TO_ID))
                .where(NETMAIL.TO_ID.eq(userId))
                .and(NETMAIL.TO_DELETED.eq(false))
                .orderBy(NETMAIL.SENT_AT.desc())
                .limit(9)
                .fetch(r -> new NetmailMessage(
                        r.get(NETMAIL.ID),
                        r.get(NETMAIL.FROM_ID),
                        r.get(NETMAIL.TO_ID),
                        r.get(fromUsers.HANDLE),
                        r.get(toUsers.HANDLE),
                        r.get(NETMAIL.SUBJECT),
                        r.get(NETMAIL.BODY),
                        r.get(NETMAIL.SENT_AT),
                        r.get(NETMAIL.READ_AT)));
    }

    /** Outbox view: not-deleted-by-sender, newest first. Capped to 9 for [1-9] selector. */
    public List<NetmailMessage> outbox(long userId) {
        var fromUsers = USERS.as("fu");
        var toUsers = USERS.as("tu");
        return dsl.select(NETMAIL.ID, NETMAIL.FROM_ID, NETMAIL.TO_ID,
                        fromUsers.HANDLE, toUsers.HANDLE,
                        NETMAIL.SUBJECT, NETMAIL.BODY,
                        NETMAIL.SENT_AT, NETMAIL.READ_AT)
                .from(NETMAIL)
                .join(fromUsers).on(fromUsers.ID.eq(NETMAIL.FROM_ID))
                .join(toUsers).on(toUsers.ID.eq(NETMAIL.TO_ID))
                .where(NETMAIL.FROM_ID.eq(userId))
                .and(NETMAIL.FROM_DELETED.eq(false))
                .orderBy(NETMAIL.SENT_AT.desc())
                .limit(9)
                .fetch(r -> new NetmailMessage(
                        r.get(NETMAIL.ID),
                        r.get(NETMAIL.FROM_ID),
                        r.get(NETMAIL.TO_ID),
                        r.get(fromUsers.HANDLE),
                        r.get(toUsers.HANDLE),
                        r.get(NETMAIL.SUBJECT),
                        r.get(NETMAIL.BODY),
                        r.get(NETMAIL.SENT_AT),
                        r.get(NETMAIL.READ_AT)));
    }

    /** Find by id; only readable if the user is sender OR recipient. */
    public Optional<NetmailMessage> findOwned(long id, long userId) {
        var fromUsers = USERS.as("fu");
        var toUsers = USERS.as("tu");
        return dsl.select(NETMAIL.ID, NETMAIL.FROM_ID, NETMAIL.TO_ID,
                        fromUsers.HANDLE, toUsers.HANDLE,
                        NETMAIL.SUBJECT, NETMAIL.BODY,
                        NETMAIL.SENT_AT, NETMAIL.READ_AT)
                .from(NETMAIL)
                .join(fromUsers).on(fromUsers.ID.eq(NETMAIL.FROM_ID))
                .join(toUsers).on(toUsers.ID.eq(NETMAIL.TO_ID))
                .where(NETMAIL.ID.eq(id))
                .and(NETMAIL.FROM_ID.eq(userId).and(NETMAIL.FROM_DELETED.eq(false))
                        .or(NETMAIL.TO_ID.eq(userId).and(NETMAIL.TO_DELETED.eq(false))))
                .fetchOptional()
                .map(r -> new NetmailMessage(
                        r.get(NETMAIL.ID),
                        r.get(NETMAIL.FROM_ID),
                        r.get(NETMAIL.TO_ID),
                        r.get(fromUsers.HANDLE),
                        r.get(toUsers.HANDLE),
                        r.get(NETMAIL.SUBJECT),
                        r.get(NETMAIL.BODY),
                        r.get(NETMAIL.SENT_AT),
                        r.get(NETMAIL.READ_AT)));
    }

    /** Mark as read by the recipient. No-op if already read or user is the sender. */
    public void markRead(long id, long userId) {
        dsl.update(NETMAIL)
                .set(NETMAIL.READ_AT, OffsetDateTime.now(ZoneOffset.UTC))
                .where(NETMAIL.ID.eq(id))
                .and(NETMAIL.TO_ID.eq(userId))
                .and(NETMAIL.READ_AT.isNull())
                .execute();
    }

    /** Soft-delete from the appropriate side. Idempotent. */
    public void softDelete(long id, long userId) {
        dsl.update(NETMAIL)
                .set(NETMAIL.TO_DELETED,
                        DSL.case_().when(NETMAIL.TO_ID.eq(userId), true).else_(NETMAIL.TO_DELETED))
                .set(NETMAIL.FROM_DELETED,
                        DSL.case_().when(NETMAIL.FROM_ID.eq(userId), true).else_(NETMAIL.FROM_DELETED))
                .where(NETMAIL.ID.eq(id))
                .and(NETMAIL.FROM_ID.eq(userId).or(NETMAIL.TO_ID.eq(userId)))
                .execute();
    }

    public int countUnread(long userId) {
        Integer n = dsl.selectCount()
                .from(NETMAIL)
                .where(NETMAIL.TO_ID.eq(userId))
                .and(NETMAIL.READ_AT.isNull())
                .and(NETMAIL.TO_DELETED.eq(false))
                .fetchOne(0, Integer.class);
        return n == null ? 0 : n;
    }
}
