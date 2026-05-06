package io.aeyer.voidcore.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * On first boot, if no real sysop user exists, create one from
 * {@code VOIDCORE_SYSOP_HANDLE} + {@code VOIDCORE_SYSOP_INITIAL_PASSWORD} env
 * vars per SPEC §3 "Bootstrap nuance" and §10 Phase 1.
 *
 * <p>A Flyway {@code afterEachMigrate} callback plants a placeholder sysop
 * row with {@code pw_hash = 'BOOTSTRAP_SENTINEL'} on a fresh DB so
 * V6__documents_substrate.sql has a non-NULL fallback
 * {@code documents.author_id}. This runner detects that sentinel and
 * rewrites it into the real sysop in-place, repointing any FK refs that
 * V6's backfill created.
 *
 * <p>Idempotent — once any user has {@code is_sysop=true} with a non-sentinel
 * hash this is a no-op on every subsequent boot. The expectation is that
 * the sysop changes the password on first login, after which the env var
 * is irrelevant; ops can remove it from the deploy {@code .env} file once
 * that has happened.
 *
 * <p>If either env var is blank (e.g. dev shell), the bootstrap silently
 * skips so the app still boots — a missing sysop is not an error here, it
 * just means there's no admin (the sentinel placeholder, if any, stays put;
 * its hash is not a valid argon2id encoding so nobody can authenticate).
 */
public class SysopBootstrap implements ApplicationRunner {

    static final String SENTINEL_HASH = "BOOTSTRAP_SENTINEL";

    private static final Logger log = LoggerFactory.getLogger(SysopBootstrap.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final PasswordHasher hasher;
    private final SysopProperties props;

    public SysopBootstrap(NamedParameterJdbcTemplate jdbc,
                          PasswordHasher hasher,
                          SysopProperties props) {
        this.jdbc = jdbc;
        this.hasher = hasher;
        this.props = props;
    }

    @Override
    public void run(org.springframework.boot.ApplicationArguments args) {
        if (!props.configured()) {
            log.info("Sysop bootstrap: VOIDCORE_SYSOP_HANDLE / VOIDCORE_SYSOP_INITIAL_PASSWORD blank — skipping");
            return;
        }
        Integer realCount = jdbc.queryForObject(
                "SELECT count(*)::int FROM users " +
                        "WHERE is_sysop = true AND pw_hash <> :sentinel",
                new MapSqlParameterSource("sentinel", SENTINEL_HASH),
                Integer.class);
        if (realCount != null && realCount > 0) {
            log.info("Sysop bootstrap: a real sysop already exists, skipping");
            return;
        }

        String hash = hasher.hash(props.initialPassword());
        var params = new MapSqlParameterSource()
                .addValue("handle", props.handle())
                .addValue("hash", hash)
                .addValue("sentinel", SENTINEL_HASH);

        // 1. Insert/upsert the real sysop. ON CONFLICT (handle) DO UPDATE
        //    handles the case where a non-sysop user already holds the env
        //    handle (promote them) or where a previous boot left a real
        //    sysop with this handle (rotate hash).
        Long realSysopId = jdbc.queryForObject("""
                INSERT INTO users (handle, pw_hash, is_sysop)
                VALUES (:handle, :hash, true)
                ON CONFLICT (handle) DO UPDATE
                  SET pw_hash = EXCLUDED.pw_hash, is_sysop = true
                RETURNING id
                """, params, Long.class);

        // 2. Repoint any FK refs from V6 sentinel rows to the real sysop
        //    before we delete them, so referential integrity holds. Fields
        //    are limited to the ones V6 introduces; pre-V6 tables never
        //    pointed at the sentinel (it didn't exist).
        var repointParams = new MapSqlParameterSource()
                .addValue("realId", realSysopId)
                .addValue("sentinel", SENTINEL_HASH);
        jdbc.update("""
                UPDATE documents SET author_id = :realId
                WHERE author_id IN (
                  SELECT id FROM users WHERE pw_hash = :sentinel
                )
                """, repointParams);
        jdbc.update("""
                UPDATE document_revisions SET edited_by = :realId
                WHERE edited_by IN (
                  SELECT id FROM users WHERE pw_hash = :sentinel
                )
                """, repointParams);
        jdbc.update("""
                DELETE FROM document_editors
                WHERE user_id IN (
                  SELECT id FROM users WHERE pw_hash = :sentinel
                )
                """, repointParams);

        // 3. Drop any remaining sentinel rows. The id<>realId guard is
        //    belt-and-braces — if the env handle collided with V6's sentinel
        //    handle, the upsert rewrote the sentinel row's hash in place
        //    (so it no longer matches WHERE pw_hash = :sentinel), but the
        //    guard keeps us from ever deleting the row we just installed.
        jdbc.update("""
                DELETE FROM users
                WHERE pw_hash = :sentinel AND id <> :realId
                """, repointParams);

        log.warn("Sysop bootstrap: created/promoted handle={} from env. " +
                "CHANGE THIS PASSWORD ON FIRST LOGIN.", props.handle());
    }
}
