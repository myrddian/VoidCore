package io.aeyer.voidcore.social;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Evaluates {@link AchievementRepository} milestone rules for a user
 * given a triggering topic. Idempotent: re-running on a user who
 * already holds the achievement is a no-op (the repo's
 * {@code ON CONFLICT DO NOTHING} guards the insert).
 *
 * <p>v1 milestones are simple "first-X" unlocks plus call-count
 * thresholds. Future polish can drive these from the
 * {@code achievements.criteria} JSONB column once the criteria DSL
 * is designed; for now the rules are coded inline so the pattern
 * stays legible.
 */
public class AchievementAwardingService {

    private static final Logger log = LoggerFactory.getLogger(AchievementAwardingService.class);

    /**
     * Map content-topic → first-time achievement slug. Topics not in
     * this map don't trigger an unlock; "first-X" is the only rule
     * shape the v1 catalogue exposes per content kind.
     */
    private static final Map<String, String> FIRST_BY_TOPIC = Map.of(
            "oneliner.created", "first-oneliner",
            "thread.created",   "first-thread",
            "post.created",     "first-post",
            "netmail.sent",     "first-netmail",
            "document.created", "first-document",
            "poll.created",     "first-poll"
    );

    private final AchievementRepository repo;

    public AchievementAwardingService(AchievementRepository repo) {
        this.repo = repo;
    }

    /**
     * Evaluate milestones for {@code userId} given a triggering
     * {@code topic}. Returns the achievements newly awarded (empty
     * if nothing unlocked).
     */
    public List<AchievementRepository.Achievement> evaluate(long userId, String topic) {
        List<AchievementRepository.Achievement> awarded = new ArrayList<>();
        String slug = FIRST_BY_TOPIC.get(topic);
        if (slug != null) {
            tryAward(userId, slug).ifPresent(awarded::add);
        }
        return awarded;
    }

    /** Auth-pipeline path: check first-login + caller thresholds. */
    public List<AchievementRepository.Achievement> evaluateCallCount(long userId, int callCount) {
        List<AchievementRepository.Achievement> awarded = new ArrayList<>();
        if (callCount >= 1)  tryAward(userId, "first-login").ifPresent(awarded::add);
        if (callCount >= 10) tryAward(userId, "caller-10").ifPresent(awarded::add);
        if (callCount >= 100) tryAward(userId, "caller-100").ifPresent(awarded::add);
        return awarded;
    }

    private Optional<AchievementRepository.Achievement> tryAward(long userId, String slug) {
        Optional<AchievementRepository.Achievement> def = repo.findBySlug(slug);
        if (def.isEmpty()) {
            log.warn("unknown achievement slug: {}", slug);
            return Optional.empty();
        }
        boolean fresh = repo.award(userId, def.get().id());
        return fresh ? def : Optional.empty();
    }
}
