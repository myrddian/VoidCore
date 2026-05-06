package io.aeyer.voidcore.social;

import io.aeyer.voidcore.auth.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Facade for the punch-list "something happened on the BBS" pipeline
 * (#87 activity feed + #89 achievement awarding).
 *
 * <p>Content-creation call sites (post oneliner, create thread, send
 * netmail, etc.) call {@link #recordEvent} to:
 *
 * <ol>
 *   <li>append a row to {@code activity_events} for the activity feed</li>
 *   <li>let the {@link AchievementAwardingService} evaluate any
 *       milestone unlocks for that user</li>
 * </ol>
 *
 * <p>Why explicit calls instead of a bus listener: the message bus
 * (ADR-027) is intentionally payload-less and carries no actor info,
 * so a wildcard subscription would record only {@code topic+timestamp}
 * — useless for a feed that wants "who did what". The DB is the
 * source of truth for actor; the bus is for rerender triggers.
 *
 * <p>Recording failures are logged-and-swallowed: activity / achievement
 * pipelines should never block the primary write path.
 */
public class SocialEventService {

    private static final Logger log = LoggerFactory.getLogger(SocialEventService.class);

    private final ActivityEventRepository events;
    private final AchievementAwardingService awarder;
    private final UserRepository users;

    public SocialEventService(ActivityEventRepository events,
                              AchievementAwardingService awarder,
                              UserRepository users) {
        this.events = events;
        this.awarder = awarder;
        this.users = users;
    }

    /**
     * Record a user-attributed event. Appends an
     * {@code activity_events} row, then runs milestone evaluation
     * for the actor against this topic. Returns the list of
     * achievements just awarded (newly unlocked) so the caller can
     * surface a notification to the user — empty if nothing new.
     */
    public List<AchievementRepository.Achievement> recordEvent(
            String topic, long actorId, JsonNode payload) {
        try {
            events.record(topic, actorId, payload);
        } catch (RuntimeException e) {
            log.warn("activity record failed: topic={} actor={} ex={}",
                    topic, actorId, e.toString());
        }
        try {
            return awarder.evaluate(actorId, topic);
        } catch (RuntimeException e) {
            log.warn("achievement evaluate failed: topic={} actor={} ex={}",
                    topic, actorId, e.toString());
            return List.of();
        }
    }

    /**
     * Record a system event (no actor). Activity feed uses these
     * for things like sysop broadcasts; awarder is skipped because
     * achievements are user-bound.
     */
    public void recordSystemEvent(String topic, JsonNode payload) {
        try {
            events.record(topic, null, payload);
        } catch (RuntimeException e) {
            log.warn("activity record (system) failed: topic={} ex={}",
                    topic, e.toString());
        }
    }

    /**
     * Award call-count milestones from the auth pipeline. Reads
     * the user's current {@code call_count} (post-recordCall) and
     * grants caller-10 / caller-100 when the threshold is crossed.
     * Idempotent — already-held achievements stay held.
     */
    public List<AchievementRepository.Achievement> checkCallerMilestones(long userId) {
        try {
            int callCount = users.callCount(userId).orElse(0);
            return awarder.evaluateCallCount(userId, callCount);
        } catch (RuntimeException e) {
            log.warn("caller milestone evaluate failed: user={} ex={}",
                    userId, e.toString());
            return List.of();
        }
    }
}
