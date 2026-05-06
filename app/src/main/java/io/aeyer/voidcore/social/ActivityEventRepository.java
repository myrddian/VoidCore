package io.aeyer.voidcore.social;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jooq.DSLContext;
import org.jooq.JSONB;

import java.time.OffsetDateTime;
import java.util.List;

import static io.aeyer.voidcore.jooq.Tables.ACTIVITY_EVENTS;
import static io.aeyer.voidcore.jooq.Tables.USERS;

/**
 * #87 activity-event log. Persistent record of bus topic firings —
 * a single table covering every primitive so the recent-activity
 * feed is a one-table scan instead of correlation across primitives.
 */
public class ActivityEventRepository {

    public record Event(
            long id,
            String topic,
            Long actorId,
            String actorHandle,        // null when actorId is null (system events)
            JsonNode payload,
            OffsetDateTime emittedAt
    ) {}

    private final DSLContext dsl;
    private final ObjectMapper json;

    public ActivityEventRepository(DSLContext dsl, ObjectMapper json) {
        this.dsl = dsl;
        this.json = json;
    }

    public void record(String topic, Long actorId, JsonNode payload) {
        try {
            String fmText = payload == null ? "{}" : json.writeValueAsString(payload);
            dsl.insertInto(ACTIVITY_EVENTS)
                    .set(ACTIVITY_EVENTS.TOPIC, topic)
                    .set(ACTIVITY_EVENTS.ACTOR_ID, actorId)
                    .set(ACTIVITY_EVENTS.PAYLOAD, JSONB.valueOf(fmText))
                    .execute();
        } catch (Exception e) {
            // Activity log failures should never disrupt the primary
            // operation. Swallow + log so the test for screen render
            // doesn't ride on this path being healthy.
            org.slf4j.LoggerFactory.getLogger(ActivityEventRepository.class)
                    .warn("activity event record failed: {}", e.toString());
        }
    }

    /**
     * Most-recent {@code limit} events newest-first. Joins to
     * {@code users} for the actor handle (left-joined since
     * actor_id is nullable for system events).
     */
    public List<Event> recent(int limit) {
        return dsl.select(ACTIVITY_EVENTS.ID, ACTIVITY_EVENTS.TOPIC,
                        ACTIVITY_EVENTS.ACTOR_ID, USERS.HANDLE,
                        ACTIVITY_EVENTS.PAYLOAD, ACTIVITY_EVENTS.EMITTED_AT)
                .from(ACTIVITY_EVENTS)
                .leftJoin(USERS).on(USERS.ID.eq(ACTIVITY_EVENTS.ACTOR_ID))
                .orderBy(ACTIVITY_EVENTS.EMITTED_AT.desc())
                .limit(limit)
                .fetch(r -> {
                    JsonNode p;
                    try {
                        p = json.readTree(r.get(ACTIVITY_EVENTS.PAYLOAD).data());
                    } catch (Exception e) {
                        p = json.createObjectNode();
                    }
                    return new Event(
                            r.get(ACTIVITY_EVENTS.ID),
                            r.get(ACTIVITY_EVENTS.TOPIC),
                            r.get(ACTIVITY_EVENTS.ACTOR_ID),
                            r.get(USERS.HANDLE),
                            p,
                            r.get(ACTIVITY_EVENTS.EMITTED_AT));
                });
    }
}
