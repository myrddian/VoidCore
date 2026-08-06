package io.aeyer.voidcore.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Driver;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end SessionService against a real Postgres 17 with the full
 * V1+V2+V3 migration set applied. Skips locally where Docker Desktop blocks
 * Testcontainers; runs in CI.
 *
 * <p>Each test gets a fresh user and the sessions table is truncated in
 * {@link #cleanSessions} so order doesn't matter. {@link Clock} is pinned
 * via {@link #at(String)} where time matters.
 */
@Testcontainers(disabledWithoutDocker = true)
class SessionServiceIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("voidcore")
            .withUsername("postgres")
            .withPassword("postgres")
            .withInitScript("init-extensions.sql");

    static DataSource dataSource;
    static NamedParameterJdbcTemplate jdbc;
    static DSLContext dsl;
    static ObjectMapper json;
    static SessionRepository repo;
    static long userId;

    @BeforeAll
    static void setup() throws Exception {
        Driver driver = (Driver) Class.forName("org.postgresql.Driver")
                .getDeclaredConstructor().newInstance();
        dataSource = new SimpleDriverDataSource(driver,
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new NamedParameterJdbcTemplate(dataSource);
        dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
        json = new ObjectMapper();

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        repo = new SessionRepository(dsl, json);
        userId = jdbc.queryForObject("""
                INSERT INTO users (handle, pw_hash) VALUES ('TRINITY', 'x') RETURNING id
                """, new MapSqlParameterSource(), Long.class);
    }

    @BeforeEach
    void cleanSessions() {
        jdbc.getJdbcTemplate().update("TRUNCATE sessions");
    }

    /**
     * Anchor for every pinned clock in this class, captured once per run.
     *
     * <p>These clocks used to be hardcoded absolute instants around
     * 2026-04-29. That is only safe while real time stays close to them:
     * {@link SessionRepository} deliberately uses the <em>database's</em>
     * wall clock for the active-row and prune predicates, and
     * {@code sessions.created_at} defaults to the DB's {@code now()}. So a
     * session "created" at a pinned instant expires 30 days later on a
     * fixed calendar date, and once wall-clock time passed it every
     * expiry-sensitive assertion inverted. The suite went red on
     * 2026-05-29 and stayed red — a failure with no commit behind it.
     *
     * <p>Truncated to whole seconds on purpose. Postgres {@code timestamptz}
     * stores microseconds, while {@code Instant.now()} carries nanoseconds on
     * Linux (and usually only microseconds on macOS) — so an untruncated
     * anchor round-trips through the database lossily and
     * {@code lastSeenAt} compares unequal on CI while passing locally. The
     * old hardcoded literals had no sub-second digits, which is why they
     * never hit this.
     *
     * <p>Anchoring to real now keeps the pinned clock and the database's
     * clock in the same relationship on every run, forever. Tests express
     * time as an offset — "a day later", "past the TTL" — which is what
     * they always meant.
     */
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    /** Service whose clock reads {@code NOW + offset}. */
    private SessionService serviceAt(Duration offset) {
        Clock pinned = Clock.fixed(NOW.plus(offset), ZoneOffset.UTC);
        return new SessionService(repo, new SessionProperties(30), pinned);
    }

    /** Service whose clock reads real now — the common case. */
    private SessionService serviceNow() {
        return serviceAt(Duration.ZERO);
    }

    @Test
    void createReturnsHexTokenWithDefaultScreen() {
        Session s = serviceNow().create(userId, "127.0.0.1", "test/1.0");

        assertThat(s.token()).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(s.userId()).isEqualTo(userId);
        assertThat(s.currentScreen().get("kind").asText()).isEqualTo("menu");
        assertThat(s.expiresAt()).isAfter(s.createdAt());
        assertThat(s.ip()).isEqualTo("127.0.0.1");
        assertThat(s.userAgent()).isEqualTo("test/1.0");
    }

    @Test
    void resumeSlidesTtlAndUpdatesLastSeen() {
        SessionService createdAt = serviceNow();
        Session created = createdAt.create(userId, "10.0.0.1", "ua");

        SessionService laterAt = serviceAt(Duration.ofDays(1));
        Optional<Session> resumed = laterAt.resume(created.token());

        assertThat(resumed).isPresent();
        // last_seen_at slid to the resume moment
        assertThat(resumed.get().lastSeenAt().toInstant())
                .isEqualTo(NOW.plus(Duration.ofDays(1)));
        // expires_at slid to resume moment + 30 days, beyond the original
        assertThat(resumed.get().expiresAt()).isAfter(created.expiresAt());
    }

    @Test
    void resumeReturnsEmptyForExpiredToken() {
        SessionService createdAt = serviceAt(Duration.ofDays(-31));
        Session created = createdAt.create(userId, "127.0.0.1", "ua");

        // 31 days later — original 30-day TTL has lapsed, no slide possible
        SessionService laterAt = serviceNow();
        assertThat(laterAt.resume(created.token())).isEmpty();
    }

    @Test
    void resumeReturnsEmptyForUnknownToken() {
        assertThat(serviceNow().resume("does-not-exist")).isEmpty();
        assertThat(serviceNow().resume("")).isEmpty();
        assertThat(serviceNow().resume(null)).isEmpty();
    }

    @Test
    void updateScreenPersistsAndRoundTrips() {
        SessionService svc = serviceNow();
        Session s = svc.create(userId, "127.0.0.1", "ua");

        ObjectNode threadView = json.createObjectNode().put("kind", "thread").put("id", 42);
        boolean ok = svc.updateScreen(s.token(), threadView);
        assertThat(ok).isTrue();

        JsonNode persisted = svc.peek(s.token()).orElseThrow().currentScreen();
        assertThat(persisted.get("kind").asText()).isEqualTo("thread");
        assertThat(persisted.get("id").asInt()).isEqualTo(42);
    }

    @Test
    void updateScreenReturnsFalseForUnknownToken() {
        SessionService svc = serviceNow();
        ObjectNode dummy = json.createObjectNode().put("kind", "menu");
        assertThat(svc.updateScreen("nope", dummy)).isFalse();
    }

    @Test
    void invalidateRemovesTheRow() {
        SessionService svc = serviceNow();
        Session s = svc.create(userId, "127.0.0.1", "ua");

        svc.invalidate(s.token());
        assertThat(svc.peek(s.token())).isEmpty();
        assertThat(svc.resume(s.token())).isEmpty();
    }

    @Test
    void pruneExpiredDeletesOnlyExpiredRows() {
        SessionService earlyClock = serviceAt(Duration.ofDays(-45));
        Session expired = earlyClock.create(userId, "127.0.0.1", "ua-1");

        SessionService recentClock = serviceNow();
        Session live = recentClock.create(userId, "127.0.0.1", "ua-2");

        // Prune at now: the early session's 30-day TTL lapsed 15 days ago,
        // the recent one has 30 days left.
        SessionService pruneClock = serviceNow();
        int removed = pruneClock.pruneExpired();

        assertThat(removed).isEqualTo(1);
        // The expired token is gone
        assertThat(jdbc.getJdbcTemplate().queryForObject(
                "SELECT count(*) FROM sessions WHERE token = ?", Long.class, expired.token()))
                .isZero();
        // The live one remains
        assertThat(jdbc.getJdbcTemplate().queryForObject(
                "SELECT count(*) FROM sessions WHERE token = ?", Long.class, live.token()))
                .isEqualTo(1L);
    }

    @Test
    void tokensAreUniqueAcrossManyCreates() {
        SessionService svc = serviceNow();
        // Cheap sanity check on the SecureRandom path; not a statistical proof.
        java.util.Set<String> tokens = new java.util.HashSet<>();
        for (int i = 0; i < 50; i++) {
            tokens.add(svc.create(userId, "127.0.0.1", "ua").token());
        }
        assertThat(tokens).hasSize(50);
    }
}
