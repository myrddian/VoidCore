package io.aeyer.voidcore.social;

import org.jooq.DSLContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.aeyer.voidcore.jooq.Tables.REACTIONS;

/**
 * #88 reactions repo. Polymorphic over content type via
 * {@code (target_type, target_id)}. The schema's UNIQUE on
 * {@code (target_type, target_id, user_id, reaction)} prevents
 * duplicate-reaction-by-user; the repo uses {@code ON CONFLICT
 * DO NOTHING} to make adds idempotent.
 *
 * <h2>Target types</h2>
 *
 * <p>App-layer convention (no DB constraint): {@code "oneliner"},
 * {@code "bulletin"}, {@code "post"}, {@code "file"},
 * {@code "document"}. Adding new types is a code-only change.
 */
public class ReactionRepository {

    public record ReactionTally(String reaction, long count) {}

    private final DSLContext dsl;

    public ReactionRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** Idempotent add. Returns true if a row was inserted. */
    public boolean add(String targetType, long targetId, long userId, String reaction) {
        return dsl.insertInto(REACTIONS)
                .set(REACTIONS.TARGET_TYPE, targetType)
                .set(REACTIONS.TARGET_ID, targetId)
                .set(REACTIONS.USER_ID, userId)
                .set(REACTIONS.REACTION, reaction)
                .onConflictDoNothing()
                .execute() > 0;
    }

    public void remove(String targetType, long targetId, long userId, String reaction) {
        dsl.deleteFrom(REACTIONS)
                .where(REACTIONS.TARGET_TYPE.eq(targetType))
                .and(REACTIONS.TARGET_ID.eq(targetId))
                .and(REACTIONS.USER_ID.eq(userId))
                .and(REACTIONS.REACTION.eq(reaction))
                .execute();
    }

    /**
     * Tally counts grouped by reaction kind for a given target.
     * Order: count desc, kind asc — stable for rendering.
     */
    public List<ReactionTally> talliesFor(String targetType, long targetId) {
        return dsl.select(REACTIONS.REACTION, org.jooq.impl.DSL.count())
                .from(REACTIONS)
                .where(REACTIONS.TARGET_TYPE.eq(targetType))
                .and(REACTIONS.TARGET_ID.eq(targetId))
                .groupBy(REACTIONS.REACTION)
                .orderBy(org.jooq.impl.DSL.count().desc(), REACTIONS.REACTION.asc())
                .fetch(r -> new ReactionTally(r.value1(), r.value2().longValue()));
    }

    /**
     * Summary form for headers etc. — returns
     * {@code {"+1": 3, "fire": 1}} as a {@link LinkedHashMap}
     * preserving the count-desc / alpha order.
     */
    public Map<String, Long> summaryFor(String targetType, long targetId) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (ReactionTally t : talliesFor(targetType, targetId)) {
            out.put(t.reaction(), t.count());
        }
        return out;
    }

    /** True if {@code userId} has reacted with the given kind. */
    public boolean userReactedWith(String targetType, long targetId,
                                   long userId, String reaction) {
        return dsl.fetchExists(
                dsl.selectOne()
                        .from(REACTIONS)
                        .where(REACTIONS.TARGET_TYPE.eq(targetType))
                        .and(REACTIONS.TARGET_ID.eq(targetId))
                        .and(REACTIONS.USER_ID.eq(userId))
                        .and(REACTIONS.REACTION.eq(reaction)));
    }
}
