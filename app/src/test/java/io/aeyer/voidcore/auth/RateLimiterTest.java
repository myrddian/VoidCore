package io.aeyer.voidcore.auth;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterTest {

    private MutableClock clock;

    private RateLimiter newLimiter() {
        clock = new MutableClock(Instant.parse("2026-04-29T00:00:00Z"));
        return new RateLimiter(RateLimitProperties.defaults(), clock);
    }

    @Test
    void loginAllowsUpToFourFailuresThenLocksOnFifth() {
        RateLimiter rl = newLimiter();
        String ip = "10.0.0.1";
        for (int i = 0; i < 4; i++) {
            assertThat(rl.recordLoginFailure(ip)).isInstanceOf(RateLimitDecision.Allowed.class);
        }
        // The 5th failure crosses the threshold and locks the IP out.
        RateLimitDecision fifth = rl.recordLoginFailure(ip);
        assertThat(fifth).isInstanceOf(RateLimitDecision.Denied.class);
        assertThat(((RateLimitDecision.Denied) fifth).retryAfterMs())
                .isBetween(14L * 60_000, 15L * 60_000);

        // Subsequent checkLogin returns Denied with a similar retry hint.
        assertThat(rl.checkLogin(ip)).isInstanceOf(RateLimitDecision.Denied.class);
    }

    @Test
    void loginLockoutClearsAfterLockoutWindow() {
        RateLimiter rl = newLimiter();
        String ip = "10.0.0.2";
        for (int i = 0; i < 5; i++) rl.recordLoginFailure(ip);
        clock.advanceMinutes(16);
        assertThat(rl.checkLogin(ip)).isInstanceOf(RateLimitDecision.Allowed.class);
    }

    @Test
    void clearLoginFailuresResetsCounter() {
        RateLimiter rl = newLimiter();
        String ip = "10.0.0.3";
        rl.recordLoginFailure(ip);
        rl.recordLoginFailure(ip);
        rl.clearLoginFailures(ip);
        // Four more failures should now be fine
        for (int i = 0; i < 4; i++) {
            assertThat(rl.recordLoginFailure(ip)).isInstanceOf(RateLimitDecision.Allowed.class);
        }
    }

    @Test
    void failuresOlderThanWindowDoNotCount() {
        RateLimiter rl = newLimiter();
        String ip = "10.0.0.4";
        for (int i = 0; i < 4; i++) rl.recordLoginFailure(ip);
        // 16 minutes later, the four failures are outside the 15-min window
        clock.advanceMinutes(16);
        // So a fresh failure should still be Allowed (not the 5th in-window)
        assertThat(rl.recordLoginFailure(ip)).isInstanceOf(RateLimitDecision.Allowed.class);
    }

    @Test
    void registrationCapsAtThreePerHour() {
        RateLimiter rl = newLimiter();
        String ip = "10.0.0.5";
        for (int i = 0; i < 3; i++) {
            assertThat(rl.checkAndRecordRegistration(ip))
                    .isInstanceOf(RateLimitDecision.Allowed.class);
        }
        assertThat(rl.checkAndRecordRegistration(ip))
                .isInstanceOf(RateLimitDecision.Denied.class);
    }

    @Test
    void postBurstFloorRejectsRapidSecondMessage() {
        RateLimiter rl = newLimiter();
        long uid = 42;
        assertThat(rl.checkAndRecordPost(uid, RateLimiter.PostKind.CHAT))
                .isInstanceOf(RateLimitDecision.Allowed.class);
        // 100ms later — under the 1s burst floor
        clock.advanceMillis(100);
        RateLimitDecision second = rl.checkAndRecordPost(uid, RateLimiter.PostKind.CHAT);
        assertThat(second).isInstanceOf(RateLimitDecision.Denied.class);
        assertThat(((RateLimitDecision.Denied) second).retryAfterMs()).isLessThanOrEqualTo(900);
    }

    @Test
    void postPerMinuteCapHoldsAfterBurst() {
        RateLimiter rl = newLimiter();
        long uid = 42;
        // 10 posts spaced 1.1s apart — all fit under both the burst floor
        // (>= 1s) and the per-minute cap (10).
        for (int i = 0; i < 10; i++) {
            assertThat(rl.checkAndRecordPost(uid, RateLimiter.PostKind.CHAT))
                    .isInstanceOf(RateLimitDecision.Allowed.class);
            clock.advanceMillis(1_100);
        }
        // The 11th — still within the 60s window — must be Denied.
        RateLimitDecision eleventh = rl.checkAndRecordPost(uid, RateLimiter.PostKind.CHAT);
        assertThat(eleventh).isInstanceOf(RateLimitDecision.Denied.class);
    }

    @Test
    void postKindsHaveSeparateBuckets() {
        RateLimiter rl = newLimiter();
        long uid = 42;
        // Fill chat to its limit, oneliner should still be unaffected
        for (int i = 0; i < 10; i++) {
            rl.checkAndRecordPost(uid, RateLimiter.PostKind.CHAT);
            clock.advanceMillis(1_100);
        }
        assertThat(rl.checkAndRecordPost(uid, RateLimiter.PostKind.ONELINER))
                .isInstanceOf(RateLimitDecision.Allowed.class);
    }

    /** Test-only mutable clock; production uses Clock.systemUTC(). */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) { this.now = start; }

        @Override public Instant instant() { return now; }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }

        void advanceMinutes(int m) { now = now.plusSeconds(m * 60L); }
        void advanceMillis(long ms) { now = now.plusMillis(ms); }
    }
}
