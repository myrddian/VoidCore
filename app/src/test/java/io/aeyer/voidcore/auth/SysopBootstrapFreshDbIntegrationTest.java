package io.aeyer.voidcore.auth;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Driver;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for the V6 fresh-DB bootstrap problem: Flyway runs
 * before SysopBootstrap, so a clean container has no sysop yet when
 * V6__documents_substrate.sql tries to backfill bulletins/files into
 * {@code documents}. The previous V6 raised an exception in that
 * scenario, which made {@code bootRun} unrecoverable on a fresh
 * container with VOIDCORE_SYSOP_HANDLE / VOIDCORE_SYSOP_INITIAL_PASSWORD set.
 *
 * <p>The fix: a Flyway {@code afterEachMigrate} callback plants a
 * placeholder sysop with a sentinel pw_hash when none exists, and
 * SysopBootstrap rewrites the sentinel into the real sysop on first
 * boot, repointing FK refs along the way.
 */
@Testcontainers(disabledWithoutDocker = true)
class SysopBootstrapFreshDbIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("voidcore")
            .withUsername("postgres")
            .withPassword("postgres")
            .withInitScript("init-extensions.sql");

    static NamedParameterJdbcTemplate jdbc;
    static PasswordHasher hasher;

    @BeforeAll
    static void migrateFreshDb() throws Exception {
        Driver driver = (Driver) Class.forName("org.postgresql.Driver")
                .getDeclaredConstructor().newInstance();
        DataSource ds = new SimpleDriverDataSource(driver,
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new NamedParameterJdbcTemplate(ds);
        hasher = new PasswordHasher(new Argon2Properties(1024, 1, 1));

        // End-to-end Flyway run on an empty schema — no sysop seeded
        // beforehand. This is the exact ordering ./gradlew bootRun
        // produces on a fresh container.
        Flyway.configure().dataSource(ds).locations("classpath:db/migration")
                .load().migrate();

        // Now run SysopBootstrap with the env vars set, mirroring Spring's
        // ApplicationRunner stage of the boot lifecycle.
        new SysopBootstrap(jdbc, hasher,
                new SysopProperties("SYSOP", "letmein-please-rotate"))
                .run(new DefaultApplicationArguments());
    }

    @Test
    void flywayMigrationCompletesOnFreshDatabase() {
        // If V6 raised, @BeforeAll would have thrown and the whole class
        // would be marked errored. Reaching this test means Flyway
        // applied V6 cleanly with no sysop seeded beforehand.
        Integer migrationCount = jdbc.queryForObject(
                "SELECT count(*)::int FROM flyway_schema_history WHERE success = true",
                new MapSqlParameterSource(), Integer.class);
        assertThat(migrationCount).isNotNull();
        assertThat(migrationCount).isGreaterThanOrEqualTo(6);
    }

    @Test
    void sysopUserIsCreatedAsSysopWithArgon2idHash() {
        String hash = jdbc.queryForObject(
                "SELECT pw_hash FROM users WHERE handle = 'SYSOP'",
                new MapSqlParameterSource(), String.class);
        assertThat(hash).startsWith("$argon2id$");
        assertThat(hasher.verify(hash, "letmein-please-rotate")).isTrue();

        Boolean isSysop = jdbc.queryForObject(
                "SELECT is_sysop FROM users WHERE handle = 'SYSOP'",
                new MapSqlParameterSource(), Boolean.class);
        assertThat(isSysop).isTrue();
    }

    @Test
    void noSentinelSysopRemainsAfterBootstrap() {
        Integer sentinelRows = jdbc.queryForObject(
                "SELECT count(*)::int FROM users WHERE pw_hash = 'BOOTSTRAP_SENTINEL'",
                new MapSqlParameterSource(), Integer.class);
        assertThat(sentinelRows).isZero();
    }

    @Test
    void exactlyOneSysopExistsAfterBootstrap() {
        Integer sysopCount = jdbc.queryForObject(
                "SELECT count(*)::int FROM users WHERE is_sysop = true",
                new MapSqlParameterSource(), Integer.class);
        assertThat(sysopCount).isEqualTo(1);
    }

    @Test
    void v6BackfilledDocumentsPointAtTheRealSysop() {
        // V6 created documents with author_id = sentinel.id. The bootstrap
        // must have repointed those refs to the new sysop, otherwise the
        // sentinel deletion would have failed with an FK violation (or, if
        // skipped, the docs would point at a non-existent user). Verify
        // every backfilled document now points at the real SYSOP user.
        Long sysopId = jdbc.queryForObject(
                "SELECT id FROM users WHERE handle = 'SYSOP'",
                new MapSqlParameterSource(), Long.class);
        assertThat(sysopId).isNotNull();

        Integer docsWithSysopAuthor = jdbc.queryForObject(
                "SELECT count(*)::int FROM documents WHERE author_id = :id",
                new MapSqlParameterSource("id", sysopId), Integer.class);
        Integer totalDocs = jdbc.queryForObject(
                "SELECT count(*)::int FROM documents",
                new MapSqlParameterSource(), Integer.class);
        // V2 seeds 3 bulletins + 7 files → 10 backfilled docs, all
        // attributed to the (formerly sentinel, now SYSOP) sysop.
        assertThat(totalDocs).isPositive();
        assertThat(docsWithSysopAuthor).isEqualTo(totalDocs);
    }
}
