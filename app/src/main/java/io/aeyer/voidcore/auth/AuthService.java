package io.aeyer.voidcore.auth;

import io.aeyer.voidcore.ws.protocol.ClientMessage;
import io.aeyer.voidcore.ws.protocol.ServerMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

/**
 * Glue between the protocol layer (#14), the password hasher (#15), the
 * session service (#16), and the rate limiter (#17). Returns either a
 * concrete {@link Outcome} the dispatcher can ship as a server message, or
 * — for valid credentials — a {@link Session} the caller stamps onto the
 * connection.
 *
 * <p>Failure modes follow SPEC §4.7 error codes:
 * <ul>
 *   <li>{@code RATE_LIMITED} — login or registration rate-limit decision Denied
 *       (carries retry_after_ms back to the client)</li>
 *   <li>{@code INVALID_CREDENTIALS} — handle not found OR password mismatch
 *       (no enumeration: same wording either way)</li>
 *   <li>{@code BANNED} — user.is_banned = true</li>
 *   <li>{@code HANDLE_TAKEN} — register hit the CITEXT unique constraint</li>
 *   <li>{@code HANDLE_INVALID} — register hit the CHECK regex constraint</li>
 * </ul>
 *
 * <p>Every login attempt is recorded in {@code login_attempts} per SPEC §5.
 * Successful logins clear the failure counter for the IP.
 */
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    public sealed interface Outcome {}

    /** Auth succeeded — caller should attach the user to the session and emit auth.ok. */
    public record Success(UserRepository.UserRow user, Session session,
                          java.time.OffsetDateTime priorLastCallAt) implements Outcome {
        /** Backwards-compatible 2-arg ctor (priorLastCallAt = null). */
        public Success(UserRepository.UserRow user, Session session) {
            this(user, session, null);
        }
    }

    /** Auth failed — caller emits auth.err with the carried code/message. */
    public record Failure(String code, String message, String field, Long retryAfterMs) implements Outcome {}

    private final UserRepository users;
    private final SessionService sessions;
    private final PasswordHasher hasher;
    private final RateLimiter rateLimiter;
    private final LoginAttemptRepository attempts;
    private final CounterRepository counters;
    private final io.aeyer.voidcore.presence.LastCallerRepository lastCallers;
    private final io.aeyer.voidcore.monitoring.VoidCoreMeters meters;

    public AuthService(UserRepository users,
                       SessionService sessions,
                       PasswordHasher hasher,
                       RateLimiter rateLimiter,
                       LoginAttemptRepository attempts,
                       CounterRepository counters,
                       io.aeyer.voidcore.presence.LastCallerRepository lastCallers,
                       io.aeyer.voidcore.monitoring.VoidCoreMeters meters) {
        this.users = users;
        this.sessions = sessions;
        this.hasher = hasher;
        this.rateLimiter = rateLimiter;
        this.attempts = attempts;
        this.counters = counters;
        this.lastCallers = lastCallers;
        this.meters = meters;
    }

    public Outcome login(ClientMessage.AuthLogin req, String ip, String userAgent) {
        // 1. Rate-limit gate
        RateLimitDecision rl = rateLimiter.checkLogin(ip);
        if (rl instanceof RateLimitDecision.Denied d) {
            attempts.record(ip, req.handle(), false);
            meters.recordLoginRateLimited();
            meters.recordLogin(false);
            return rateLimited(d);
        }

        // 2. Look up user
        Optional<UserRepository.UserRow> maybe = users.findByHandle(req.handle());
        if (maybe.isEmpty()) {
            rateLimiter.recordLoginFailure(ip);
            attempts.record(ip, req.handle(), false);
            meters.recordLogin(false);
            return invalidCredentials();
        }
        UserRepository.UserRow u = maybe.get();
        if (u.isBanned()) {
            attempts.record(ip, req.handle(), false);
            meters.recordLogin(false);
            return new Failure("BANNED", "this handle is banned", null, null);
        }

        // 3. Verify password
        if (!hasher.verify(u.pwHash(), req.password())) {
            RateLimitDecision afterFail = rateLimiter.recordLoginFailure(ip);
            attempts.record(ip, req.handle(), false);
            meters.recordLogin(false);
            if (afterFail instanceof RateLimitDecision.Denied d) {
                meters.recordLoginRateLimited();
                return rateLimited(d);
            }
            return invalidCredentials();
        }

        // 4. Success — clear rate-limit, create session, log attempt, bump
        //    caller count, write the last_callers row for the live presence
        //    "last callers" screen.
        rateLimiter.clearLoginFailures(ip);
        Session s = sessions.create(u.id(), ip, userAgent);
        // Ticket #85: snapshot last_call_at BEFORE recordCall bumps
        // it so the post-auth login-summary screen can compute
        // "what's new since you were last here" against the prior
        // value rather than now().
        java.time.OffsetDateTime prior = users.lastCallAt(u.id()).orElse(null);
        users.recordCall(u.id());
        attempts.record(ip, req.handle(), true);
        counters.increment(CounterRepository.CALLER_COUNT);
        lastCallers.record(u.id());
        meters.recordLogin(true);
        return new Success(u, s, prior);
    }

    public Outcome register(ClientMessage.AuthRegister req, String ip, String userAgent) {
        RateLimitDecision rl = rateLimiter.checkAndRecordRegistration(ip);
        if (rl instanceof RateLimitDecision.Denied d) {
            meters.recordRegistrationRateLimited();
            return rateLimited(d);
        }

        String hash = hasher.hash(req.password());
        long userId;
        try {
            userId = users.insert(req.handle(), hash,
                    req.location(), req.setup(), req.found_via(), req.fav_genres());
        } catch (DataIntegrityViolationException dive) {
            String msg = String.valueOf(dive.getMostSpecificCause().getMessage());
            if (msg.contains("users_handle_key") || msg.contains("duplicate key")) {
                return new Failure("HANDLE_TAKEN", "that handle is already taken", "handle", null);
            }
            if (msg.contains("users_handle_check")) {
                return new Failure("HANDLE_INVALID",
                        "handles are 3-16 chars: letters, digits, _-.", "handle", null);
            }
            log.warn("unexpected DB error on register: {}", msg);
            return new Failure("INTERNAL", "registration failed", null, null);
        }

        Session s = sessions.create(userId, ip, userAgent);
        users.recordCall(userId);
        attempts.record(ip, req.handle(), true);
        counters.increment(CounterRepository.CALLER_COUNT);
        lastCallers.record(userId);
        UserRepository.UserRow row = users.findByHandle(req.handle()).orElseThrow();
        return new Success(row, s);
    }

    /** Look up (and slide) the session for an auth.resume. */
    public Optional<Session> resume(String token) {
        Optional<Session> s = sessions.resume(token);
        s.ifPresent(session -> users.recordCall(session.userId()));
        return s;
    }

    public void logout(String token) {
        sessions.invalidate(token);
    }

    /** Helpers for the dispatcher to map an Outcome to a ServerMessage. */
    public static ServerMessage.AuthErr toAuthErr(Failure f) {
        return new ServerMessage.AuthErr(f.code(), f.message(), f.field());
    }

    private Failure invalidCredentials() {
        return new Failure("INVALID_CREDENTIALS", "bad handle or password", null, null);
    }

    private Failure rateLimited(RateLimitDecision.Denied d) {
        return new Failure("RATE_LIMITED",
                "too many attempts; try again later", null, d.retryAfterMs());
    }
}
