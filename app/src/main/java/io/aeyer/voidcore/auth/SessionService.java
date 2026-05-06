package io.aeyer.voidcore.auth;

import com.fasterxml.jackson.databind.JsonNode;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Session lifecycle per SPEC §5.
 *
 * <ul>
 *   <li><b>Token</b>: 32 random bytes from {@link SecureRandom}, hex-encoded
 *       to a 64-character string. Stored as {@code sessions.token}.</li>
 *   <li><b>TTL</b>: {@link SessionProperties#ttlDays()} days, sliding —
 *       every {@link #resume(String)} call extends {@code expires_at} by
 *       the full TTL from <i>now</i> (not from the previous expires).</li>
 *   <li><b>{@code current_screen}</b>: per-session JSONB updated on every
 *       navigation by callers via {@link #updateScreen(String, JsonNode)}
 *       so reconnects (and JVM restarts) land users back in the right area
 *       per SPEC §13's reconnect-resilience criteria.</li>
 * </ul>
 *
 * <p>{@link Clock} is injectable so tests can pin time and exercise expiry
 * without sleeping; production wires the system UTC clock.
 *
 * <p>Constructed via {@link AuthConfig}; gated on the presence of a
 * {@code DataSource}, same as {@link SessionRepository}.
 */
public class SessionService {

    private static final int TOKEN_BYTES = 32;
    private static final HexFormat HEX = HexFormat.of();

    private final SessionRepository repo;
    private final SessionProperties props;
    private final SecureRandom random;
    private final Clock clock;

    public SessionService(SessionRepository repo, SessionProperties props, Clock clock) {
        this.repo = repo;
        this.props = props;
        this.random = new SecureRandom();
        this.clock = clock;
    }

    /**
     * Create a fresh session for a successful login. The token is what the
     * client persists in {@code localStorage[voidcore:session]}; subsequent
     * reconnects send it via {@code auth.resume}.
     */
    public Session create(long userId, String ip, String userAgent) {
        String token = newToken();
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime expiresAt = now.plusDays(props.ttlDays());
        repo.insert(token, userId, ip, userAgent, expiresAt);
        // Pass the same `now` we used for expiresAt so the read-back's
        // active-row predicate (`expires_at > now`) is consistent — without
        // this, a clock-stubbed test that pretends to create a session in
        // the past would see it as "instantly expired" on read-back.
        return repo.findActive(token, now).orElseThrow(
                () -> new IllegalStateException("session disappeared after insert: token=" + token));
    }

    /**
     * Validate a token and slide the TTL. Returns the session if it exists
     * and is not expired; otherwise empty (caller should require fresh auth).
     */
    public Optional<Session> resume(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime newExpiry = now.plusDays(props.ttlDays());
        return repo.touchAndExtend(token, now, newExpiry);
    }

    /** Active read without sliding the TTL. Used for read-only checks. */
    public Optional<Session> peek(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        return repo.findActive(token);
    }

    /**
     * Persist the user's current screen so a reconnect (or JVM restart)
     * lands them back here. Caller is whichever screen handler effects a
     * navigation; for SPEC §13's "JVM restart mid-session → user re-rendered
     * to current_screen" criterion this must be called on every transition.
     */
    public boolean updateScreen(String token, JsonNode screen) {
        if (token == null || token.isBlank()) return false;
        return repo.updateCurrentScreen(token, screen);
    }

    /** Hard-delete on logout. */
    public void invalidate(String token) {
        if (token == null) return;
        repo.delete(token);
    }

    /** Sweep expired rows. Caller is the housekeeping task (separate ticket). */
    public int pruneExpired() {
        return repo.deleteExpired();
    }

    /** 32 random bytes, lowercase hex, length 64. */
    private String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return HEX.formatHex(bytes);
    }
}
