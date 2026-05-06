package io.aeyer.voidcore.social;

import org.jooq.DSLContext;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static io.aeyer.voidcore.jooq.Tables.ACHIEVEMENTS;
import static io.aeyer.voidcore.jooq.Tables.USER_ACHIEVEMENTS;

/**
 * #89 achievements repo. The catalogue lives in {@code achievements}
 * (seeded by V7 migration); per-user awards live in
 * {@code user_achievements}.
 */
public class AchievementRepository {

    public record Achievement(long id, String slug, String name, String description,
                              int points, String category, String source) {}
    public record AwardedAchievement(long id, String slug, String name, String description,
                                     int points, String category, String source,
                                     OffsetDateTime awardedAt) {}

    private final DSLContext dsl;

    public AchievementRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** Look up an achievement by slug. */
    public Optional<Achievement> findBySlug(String slug) {
        return dsl.select(ACHIEVEMENTS.ID, ACHIEVEMENTS.SLUG,
                        ACHIEVEMENTS.NAME, ACHIEVEMENTS.DESCRIPTION,
                        ACHIEVEMENTS.POINTS, ACHIEVEMENTS.CATEGORY, ACHIEVEMENTS.SOURCE)
                .from(ACHIEVEMENTS)
                .where(ACHIEVEMENTS.SLUG.eq(slug))
                .fetchOptional()
                .map(r -> new Achievement(r.value1(), r.value2(), r.value3(), r.value4(),
                        r.value5(), r.value6(), r.value7()));
    }

    /**
     * Insert-or-refresh an achievement by slug. Returns the row either way.
     * Used by the door achievement protocol so sidecars can register their
     * own achievements dynamically without requiring a DB migration; the
     * door's title/description/points/category on each unlock is the source
     * of truth and any change is reflected here on the next send.
     *
     * <p>Slug uniqueness is enforced by the schema. Door achievements are
     * namespaced by the dispatcher (e.g. {@code door:cityline-mud:first_blood})
     * so they cannot collide with BBS-native slugs.
     */
    public Achievement upsertBySlug(String slug, String name, String description,
                                    int points, String category, String source) {
        dsl.insertInto(ACHIEVEMENTS)
                .set(ACHIEVEMENTS.SLUG, slug)
                .set(ACHIEVEMENTS.NAME, name)
                .set(ACHIEVEMENTS.DESCRIPTION, description)
                .set(ACHIEVEMENTS.POINTS, points)
                .set(ACHIEVEMENTS.CATEGORY, category)
                .set(ACHIEVEMENTS.SOURCE, source)
                .onConflict(ACHIEVEMENTS.SLUG)
                .doUpdate()
                .set(ACHIEVEMENTS.NAME, name)
                .set(ACHIEVEMENTS.DESCRIPTION, description)
                .set(ACHIEVEMENTS.POINTS, points)
                .set(ACHIEVEMENTS.CATEGORY, category)
                .set(ACHIEVEMENTS.SOURCE, source)
                .execute();
        return findBySlug(slug).orElseThrow(() ->
                new IllegalStateException("upsert succeeded but achievement not found: " + slug));
    }

    /**
     * Award an achievement; idempotent via
     * {@code ON CONFLICT DO NOTHING} on the composite PK. Returns
     * true if the row was inserted (i.e. this is a fresh unlock),
     * false if the user already had it.
     */
    public boolean award(long userId, long achievementId) {
        return dsl.insertInto(USER_ACHIEVEMENTS)
                .set(USER_ACHIEVEMENTS.USER_ID, userId)
                .set(USER_ACHIEVEMENTS.ACHIEVEMENT_ID, achievementId)
                .onConflictDoNothing()
                .execute() > 0;
    }

    /** Achievements awarded to {@code userId}, newest-first. */
    public List<AwardedAchievement> awarded(long userId) {
        return dsl.select(ACHIEVEMENTS.ID, ACHIEVEMENTS.SLUG, ACHIEVEMENTS.NAME,
                        ACHIEVEMENTS.DESCRIPTION, ACHIEVEMENTS.POINTS,
                        ACHIEVEMENTS.CATEGORY, ACHIEVEMENTS.SOURCE,
                        USER_ACHIEVEMENTS.AWARDED_AT)
                .from(USER_ACHIEVEMENTS)
                .join(ACHIEVEMENTS).on(ACHIEVEMENTS.ID.eq(USER_ACHIEVEMENTS.ACHIEVEMENT_ID))
                .where(USER_ACHIEVEMENTS.USER_ID.eq(userId))
                .orderBy(USER_ACHIEVEMENTS.AWARDED_AT.desc())
                .fetch(r -> new AwardedAchievement(
                        r.value1(), r.value2(), r.value3(), r.value4(),
                        r.value5(), r.value6(), r.value7(), r.value8()));
    }

    /** Whole catalogue. Used by the screen to show "locked" entries. */
    public List<Achievement> catalogue() {
        return dsl.select(ACHIEVEMENTS.ID, ACHIEVEMENTS.SLUG,
                        ACHIEVEMENTS.NAME, ACHIEVEMENTS.DESCRIPTION,
                        ACHIEVEMENTS.POINTS, ACHIEVEMENTS.CATEGORY, ACHIEVEMENTS.SOURCE)
                .from(ACHIEVEMENTS)
                .orderBy(ACHIEVEMENTS.ID)
                .fetch(r -> new Achievement(r.value1(), r.value2(),
                        r.value3(), r.value4(),
                        r.value5(), r.value6(), r.value7()));
    }

    /** BBS-native catalogue — anything whose slug is NOT
     *  {@code door:<doorId>:<id>}. */
    public List<Achievement> bbsCatalogue() {
        return catalogue().stream()
                .filter(a -> !isDoorSlug(a.slug()))
                .toList();
    }

    /** Catalogue for a single door — slugs of the form
     *  {@code door:<doorId>:<id>}. */
    public List<Achievement> doorCatalogue(String doorId) {
        if (doorId == null || doorId.isBlank()) return List.of();
        String prefix = "door:" + doorId + ":";
        return catalogue().stream()
                .filter(a -> a.slug() != null && a.slug().startsWith(prefix))
                .toList();
    }

    /** Distinct door ids that appear in the catalogue, sorted alphabetically. */
    public List<String> doorIdsWithAchievements() {
        return catalogue().stream()
                .map(a -> doorIdFromSlug(a.slug()))
                .filter(java.util.Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
    }

    /** Parse {@code door:<doorId>:...} → {@code <doorId>}, or null if the
     *  slug doesn't follow the pattern. */
    public static String doorIdFromSlug(String slug) {
        if (slug == null || !slug.startsWith("door:")) return null;
        int second = slug.indexOf(':', 5);
        if (second <= 5) return null;
        return slug.substring(5, second);
    }

    private static boolean isDoorSlug(String slug) {
        return doorIdFromSlug(slug) != null;
    }
}
