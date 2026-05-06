package io.aeyer.voidcore.social;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AchievementAwardingService}. Verifies the
 * topic → first-* mapping and the call-count threshold rules.
 */
class AchievementAwardingServiceTest {

    AchievementRepository repo;
    AchievementAwardingService awarder;

    @BeforeEach
    void setUp() {
        repo = mock(AchievementRepository.class);
        awarder = new AchievementAwardingService(repo);
    }

    private void stubAchievement(String slug, long id) {
        when(repo.findBySlug(slug)).thenReturn(Optional.of(
                new AchievementRepository.Achievement(id, slug, slug, "desc", 0, "", "bbs")));
    }

    @Test
    void onelinerCreatedAwardsFirstOneliner() {
        stubAchievement("first-oneliner", 4);
        when(repo.award(anyLong(), eq(4L))).thenReturn(true);

        List<AchievementRepository.Achievement> awarded =
                awarder.evaluate(100L, "oneliner.created");

        assertThat(awarded).hasSize(1);
        assertThat(awarded.get(0).slug()).isEqualTo("first-oneliner");
        verify(repo).award(100L, 4);
    }

    @Test
    void evaluateReturnsEmptyWhenAlreadyAwarded() {
        stubAchievement("first-thread", 2);
        when(repo.award(anyLong(), eq(2L))).thenReturn(false);  // already had it

        List<AchievementRepository.Achievement> awarded =
                awarder.evaluate(100L, "thread.created");

        assertThat(awarded).isEmpty();
    }

    @Test
    void unknownTopicReturnsEmpty() {
        List<AchievementRepository.Achievement> awarded =
                awarder.evaluate(100L, "totally-unknown-topic");

        assertThat(awarded).isEmpty();
        verify(repo, never()).award(anyLong(), anyLong());
    }

    @Test
    void allFirstStarSlugsAreSupported() {
        for (var pair : new String[][] {
                {"oneliner.created", "first-oneliner"},
                {"thread.created",   "first-thread"},
                {"post.created",     "first-post"},
                {"netmail.sent",     "first-netmail"},
                {"document.created", "first-document"},
                {"poll.created",     "first-poll"},
        }) {
            String topic = pair[0];
            String slug = pair[1];
            stubAchievement(slug, 1);
            when(repo.award(anyLong(), eq(1L))).thenReturn(true);
            List<AchievementRepository.Achievement> awarded =
                    awarder.evaluate(100L, topic);
            assertThat(awarded).as("topic %s → %s", topic, slug)
                    .extracting(AchievementRepository.Achievement::slug)
                    .containsExactly(slug);
        }
    }

    @Test
    void callCountOneAwardsFirstLogin() {
        stubAchievement("first-login", 1);
        when(repo.award(anyLong(), eq(1L))).thenReturn(true);

        List<AchievementRepository.Achievement> awarded =
                awarder.evaluateCallCount(100L, 1);

        assertThat(awarded).extracting(AchievementRepository.Achievement::slug)
                .containsExactly("first-login");
    }

    @Test
    void callCountTenAwardsFirstLoginAndCallerTen() {
        stubAchievement("first-login", 1);
        stubAchievement("caller-10",  7);
        when(repo.award(anyLong(), eq(1L))).thenReturn(false); // already had
        when(repo.award(anyLong(), eq(7L))).thenReturn(true);

        List<AchievementRepository.Achievement> awarded =
                awarder.evaluateCallCount(100L, 10);

        // first-login still tested but already-held; only caller-10 is fresh.
        assertThat(awarded).extracting(AchievementRepository.Achievement::slug)
                .containsExactly("caller-10");
    }

    @Test
    void callCountHundredAwardsAllThree() {
        stubAchievement("first-login", 1);
        stubAchievement("caller-10",  7);
        stubAchievement("caller-100", 8);
        when(repo.award(anyLong(), eq(1L))).thenReturn(true);
        when(repo.award(anyLong(), eq(7L))).thenReturn(true);
        when(repo.award(anyLong(), eq(8L))).thenReturn(true);

        List<AchievementRepository.Achievement> awarded =
                awarder.evaluateCallCount(100L, 100);

        assertThat(awarded).extracting(AchievementRepository.Achievement::slug)
                .containsExactlyInAnyOrder("first-login", "caller-10", "caller-100");
    }

    @Test
    void callCountZeroAwardsNothing() {
        List<AchievementRepository.Achievement> awarded =
                awarder.evaluateCallCount(100L, 0);

        assertThat(awarded).isEmpty();
        verify(repo, never()).award(anyLong(), anyLong());
    }

    @Test
    void unknownSlugLogsAndSkips() {
        // No stub for "first-login" — repo returns empty.
        List<AchievementRepository.Achievement> awarded =
                awarder.evaluateCallCount(100L, 1);

        assertThat(awarded).isEmpty();
        verify(repo, never()).award(anyLong(), anyLong());
    }
}
