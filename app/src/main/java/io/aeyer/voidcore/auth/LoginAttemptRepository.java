package io.aeyer.voidcore.auth;

import io.aeyer.voidcore.repo.PgTypes;
import org.jooq.DSLContext;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static io.aeyer.voidcore.jooq.Tables.LOGIN_ATTEMPTS;

/**
 * Audit log of every login attempt per SPEC §5 ("Logged via login_attempts
 * table for forensics"). jOOQ DSL backed (ADR-005a).
 */
public class LoginAttemptRepository {

    private final DSLContext dsl;

    public LoginAttemptRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public void record(String ip, String handle, boolean success) {
        dsl.insertInto(LOGIN_ATTEMPTS)
                .set(LOGIN_ATTEMPTS.IP, PgTypes.inet(ip))
                .set(LOGIN_ATTEMPTS.HANDLE, handle)
                .set(LOGIN_ATTEMPTS.SUCCESS, success)
                .execute();
    }

    private static org.jooq.Condition ipEq(String ip) {
        return LOGIN_ATTEMPTS.IP.eq(PgTypes.inet(ip));
    }

    /** Counts failures for an IP within a rolling window — for sysop forensics. */
    public int countFailuresFor(String ip, Duration window) {
        OffsetDateTime cutoff = OffsetDateTime.now(ZoneOffset.UTC).minus(window);
        Integer n = dsl.selectCount()
                .from(LOGIN_ATTEMPTS)
                .where(ipEq(ip))
                .and(LOGIN_ATTEMPTS.SUCCESS.eq(false))
                .and(LOGIN_ATTEMPTS.AT.gt(cutoff))
                .fetchOne(0, Integer.class);
        return n == null ? 0 : n;
    }
}
