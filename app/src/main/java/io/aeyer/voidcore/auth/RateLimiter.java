package io.aeyer.voidcore.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-process rate limiting per SPEC §5, Caffeine-backed:
 *
 * <ul>
 *   <li><b>Login</b>: ≤ {@code loginFailuresPerWindow} failures per IP within
 *       {@code loginWindowMinutes}; on the threshold-th failure the IP is
 *       locked out for {@code loginLockoutMinutes}. Successful logins clear
 *       the failure counter.</li>
 *   <li><b>Registration</b>: ≤ {@code registrationsPerHour} attempts per IP
 *       per hour. No lockout — just the cap.</li>
 *   <li><b>Posting (chat / oneliners)</b>: ≤ {@code postsPerMinute} per user
 *       per 60s, with a 1-per-{@code postBurstPerSecond}-seconds burst floor
 *       so a script can't fire 10 in 50ms even if it stays under the
 *       per-minute cap.</li>
 * </ul>
 *
 * <p>State lives in-process — fine at board scale (single instance per
 * SPEC's non-goals §11). Multi-node clustering would move this to Redis
 * with the same interface; that is explicitly out of scope for v1.
 */
@Component
public class RateLimiter {

    private final RateLimitProperties props;
    private final Clock clock;

    private final Cache<String, FailureState> loginFailures;
    private final Cache<String, AtomicInteger> registrationCounts;
    private final Cache<String, Deque<Instant>> postWindows;

    public RateLimiter(RateLimitProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
        // Login: window + lockout, take the longer for cache TTL so state
        // survives long enough to honour active lockouts.
        long loginTtl = Math.max(props.loginWindowMinutes(), props.loginLockoutMinutes());
        this.loginFailures = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(loginTtl + 1))
                .maximumSize(100_000)
                .build();
        this.registrationCounts = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofHours(1).plusMinutes(1))
                .maximumSize(100_000)
                .build();
        this.postWindows = Caffeine.newBuilder()
                .expireAfterAccess(Duration.ofMinutes(2))
                .maximumSize(100_000)
                .build();
    }

    // --- Login -----------------------------------------------------------

    public RateLimitDecision checkLogin(String ip) {
        FailureState s = loginFailures.getIfPresent(ip);
        if (s == null) return RateLimitDecision.ALLOWED;
        Instant now = Instant.now(clock);
        if (s.lockoutUntil != null && now.isBefore(s.lockoutUntil)) {
            return new RateLimitDecision.Denied(
                    Duration.between(now, s.lockoutUntil).toMillis());
        }
        return RateLimitDecision.ALLOWED;
    }

    /** Record a failed login. Returns the resulting decision so callers can act. */
    public synchronized RateLimitDecision recordLoginFailure(String ip) {
        Instant now = Instant.now(clock);
        FailureState s = loginFailures.get(ip, k -> new FailureState());
        // Drop failures outside the rolling window.
        Instant cutoff = now.minus(Duration.ofMinutes(props.loginWindowMinutes()));
        s.failures.removeIf(t -> t.isBefore(cutoff));
        s.failures.addLast(now);
        if (s.failures.size() >= props.loginFailuresPerWindow()) {
            s.lockoutUntil = now.plus(Duration.ofMinutes(props.loginLockoutMinutes()));
            return new RateLimitDecision.Denied(
                    Duration.between(now, s.lockoutUntil).toMillis());
        }
        return RateLimitDecision.ALLOWED;
    }

    /** Successful login — wipe the failure history for this IP. */
    public void clearLoginFailures(String ip) {
        loginFailures.invalidate(ip);
    }

    // --- Registration ---------------------------------------------------

    public synchronized RateLimitDecision checkAndRecordRegistration(String ip) {
        AtomicInteger count = registrationCounts.get(ip, k -> new AtomicInteger(0));
        int after = count.incrementAndGet();
        if (after > props.registrationsPerHour()) {
            return new RateLimitDecision.Denied(Duration.ofHours(1).toMillis());
        }
        return RateLimitDecision.ALLOWED;
    }

    // --- Posting (chat / oneliners) -------------------------------------

    public synchronized RateLimitDecision checkAndRecordPost(long userId, PostKind kind) {
        String key = userId + ":" + kind.name();
        Deque<Instant> window = postWindows.get(key, k -> new ArrayDeque<>());
        Instant now = Instant.now(clock);
        Instant minuteAgo = now.minus(Duration.ofMinutes(1));
        // Sliding 60s window
        while (!window.isEmpty() && window.peekFirst().isBefore(minuteAgo)) {
            window.pollFirst();
        }
        // Per-second burst floor: most recent post must be at least N seconds ago
        if (!window.isEmpty()) {
            Instant last = window.peekLast();
            Duration sinceLast = Duration.between(last, now);
            Duration minGap = Duration.ofSeconds(props.postBurstPerSecond());
            if (sinceLast.compareTo(minGap) < 0) {
                return new RateLimitDecision.Denied(
                        minGap.minus(sinceLast).toMillis());
            }
        }
        if (window.size() >= props.postsPerMinute()) {
            // Oldest entry will fall out of the window in (60s - elapsed-since-oldest)
            Instant oldest = window.peekFirst();
            long retry = Duration.between(now, oldest.plus(Duration.ofMinutes(1))).toMillis();
            return new RateLimitDecision.Denied(Math.max(retry, 0));
        }
        window.addLast(now);
        return RateLimitDecision.ALLOWED;
    }

    public enum PostKind { CHAT, ONELINER }

    private static final class FailureState {
        final Deque<Instant> failures = new ArrayDeque<>();
        Instant lockoutUntil;
    }
}
