package io.aeyer.voidcore.polls;

import org.jooq.DSLContext;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static io.aeyer.voidcore.jooq.Tables.POLLS;
import static io.aeyer.voidcore.jooq.Tables.POLL_OPTIONS;
import static io.aeyer.voidcore.jooq.Tables.POLL_VOTES;
import static io.aeyer.voidcore.jooq.Tables.USERS;

/**
 * #93 polls repo. Single-choice polls — one vote per user per poll
 * (PK on {@code (poll_id, user_id)}); re-voting replaces via UPSERT.
 *
 * <p>"Closed" polls keep all rows but set {@code closed_at}; voting
 * is rejected at the screen layer (the repo accepts the write but
 * the screen gates).
 */
public class PollRepository {

    public record Poll(long id, long authorId, String authorHandle,
                       String question, OffsetDateTime createdAt,
                       OffsetDateTime closedAt) {
        public boolean isOpen() { return closedAt == null; }
    }

    public record Option(long id, long pollId, String text, int position) {}

    public record OptionTally(long optionId, String text, int position, long votes) {}

    private final DSLContext dsl;

    public PollRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * Insert a poll with its options. Options inserted in input order
     * (position 1..N). Returns the new poll id.
     */
    public long insert(long authorId, String question, List<String> options) {
        if (options == null || options.size() < 2) {
            throw new IllegalArgumentException("poll requires at least 2 options");
        }
        return dsl.transactionResult(c -> {
            var tx = c.dsl();
            Long pollId = tx.insertInto(POLLS)
                    .set(POLLS.AUTHOR_ID, authorId)
                    .set(POLLS.QUESTION, question)
                    .returningResult(POLLS.ID)
                    .fetchOne(POLLS.ID);
            if (pollId == null) {
                throw new IllegalStateException("poll insert returned no id");
            }
            int pos = 1;
            for (String text : options) {
                tx.insertInto(POLL_OPTIONS)
                        .set(POLL_OPTIONS.POLL_ID, pollId)
                        .set(POLL_OPTIONS.TEXT, text)
                        .set(POLL_OPTIONS.POSITION, pos++)
                        .execute();
            }
            return pollId;
        });
    }

    /** All polls newest-first; capped at {@code limit}. */
    public List<Poll> recent(int limit) {
        return dsl.select(POLLS.ID, POLLS.AUTHOR_ID, USERS.HANDLE,
                        POLLS.QUESTION, POLLS.CREATED_AT, POLLS.CLOSED_AT)
                .from(POLLS)
                .join(USERS).on(USERS.ID.eq(POLLS.AUTHOR_ID))
                .orderBy(POLLS.CREATED_AT.desc())
                .limit(limit)
                .fetch(r -> new Poll(r.value1(), r.value2(), r.value3(),
                        r.value4(), r.value5(), r.value6()));
    }

    public Optional<Poll> findById(long id) {
        return dsl.select(POLLS.ID, POLLS.AUTHOR_ID, USERS.HANDLE,
                        POLLS.QUESTION, POLLS.CREATED_AT, POLLS.CLOSED_AT)
                .from(POLLS)
                .join(USERS).on(USERS.ID.eq(POLLS.AUTHOR_ID))
                .where(POLLS.ID.eq(id))
                .fetchOptional()
                .map(r -> new Poll(r.value1(), r.value2(), r.value3(),
                        r.value4(), r.value5(), r.value6()));
    }

    /**
     * Options for a poll WITH per-option vote counts, ordered by
     * position. One query — left-joins votes so options with zero
     * votes still appear.
     */
    public List<OptionTally> tallies(long pollId) {
        return dsl.select(POLL_OPTIONS.ID, POLL_OPTIONS.TEXT,
                        POLL_OPTIONS.POSITION,
                        org.jooq.impl.DSL.count(POLL_VOTES.OPTION_ID))
                .from(POLL_OPTIONS)
                .leftJoin(POLL_VOTES).on(POLL_VOTES.OPTION_ID.eq(POLL_OPTIONS.ID))
                .where(POLL_OPTIONS.POLL_ID.eq(pollId))
                .groupBy(POLL_OPTIONS.ID, POLL_OPTIONS.TEXT, POLL_OPTIONS.POSITION)
                .orderBy(POLL_OPTIONS.POSITION.asc())
                .fetch(r -> new OptionTally(r.value1(), r.value2(),
                        r.value3(), r.value4().longValue()));
    }

    /**
     * Cast or change a vote. Single-choice — replaces any existing
     * vote by this user for this poll. Returns {@code true} if a
     * row was inserted/updated.
     */
    public boolean vote(long pollId, long optionId, long userId) {
        return dsl.insertInto(POLL_VOTES)
                .set(POLL_VOTES.POLL_ID, pollId)
                .set(POLL_VOTES.OPTION_ID, optionId)
                .set(POLL_VOTES.USER_ID, userId)
                .onConflict(POLL_VOTES.POLL_ID, POLL_VOTES.USER_ID)
                .doUpdate()
                .set(POLL_VOTES.OPTION_ID, optionId)
                .set(POLL_VOTES.VOTED_AT, OffsetDateTime.now())
                .execute() > 0;
    }

    /**
     * The option {@code userId} chose on this poll, or empty if
     * they haven't voted. Used to highlight the user's pick on
     * the view screen.
     */
    public Optional<Long> userVoteOption(long pollId, long userId) {
        return Optional.ofNullable(
                dsl.select(POLL_VOTES.OPTION_ID)
                        .from(POLL_VOTES)
                        .where(POLL_VOTES.POLL_ID.eq(pollId))
                        .and(POLL_VOTES.USER_ID.eq(userId))
                        .fetchOne(POLL_VOTES.OPTION_ID));
    }

    /** Total votes on a poll. Sum of all option tallies. */
    public long totalVotes(long pollId) {
        Long n = dsl.selectCount()
                .from(POLL_VOTES)
                .where(POLL_VOTES.POLL_ID.eq(pollId))
                .fetchOne(0, Long.class);
        return n == null ? 0 : n;
    }

    public void close(long pollId) {
        dsl.update(POLLS)
                .set(POLLS.CLOSED_AT, OffsetDateTime.now())
                .where(POLLS.ID.eq(pollId))
                .and(POLLS.CLOSED_AT.isNull())
                .execute();
    }

    public void delete(long pollId) {
        dsl.deleteFrom(POLLS).where(POLLS.ID.eq(pollId)).execute();
    }
}
