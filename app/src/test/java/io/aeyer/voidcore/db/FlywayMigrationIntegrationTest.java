package io.aeyer.voidcore.db;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies V1__initial_schema.sql applies cleanly against a real Postgres 17
 * with the citext + pgcrypto extensions pre-created (matching the production
 * sql/init/01-init-roles.sh contract). Asserts every expected table, the
 * handle format CHECK constraint, the JSONB default on sessions.current_screen,
 * and the oneliners length CHECK.
 *
 * Skips gracefully when no Docker daemon is reachable (e.g. dev shells where
 * Docker Desktop blocks API access for non-CLI clients). CI runs Docker in
 * the runner so the test executes there.
 */
@Testcontainers(disabledWithoutDocker = true)
class FlywayMigrationIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("voidcore")
            .withUsername("postgres")
            .withPassword("postgres")
            .withInitScript("init-extensions.sql");

    static MigrateResult migrateResult;

    @BeforeAll
    static void migrate() {
        Flyway flyway = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load();
        migrateResult = flyway.migrate();
    }

    @Test
    void migrationsAreApplied() {
        assertThat(migrateResult.success).isTrue();
        assertThat(migrateResult.migrations)
                .extracting(m -> m.version)
                .contains("1", "24");
    }

    @Test
    void messageBasesStartEmptyByDefault() throws SQLException {
        try (Connection c = connect();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT count(*) FROM message_bases")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isZero();
        }
    }

    @Test
    void releaseDocsAreNotSeededByDefault() throws SQLException {
        try (Connection c = connect();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT count(*) " +
                     "FROM documents " +
                     "WHERE type_slug = 'release' AND deleted_at IS NULL")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isZero();
        }
    }

    @Test
    void articleDocsAreNotSeededByDefault() throws SQLException {
        try (Connection c = connect();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT count(*) FROM documents " +
                     "WHERE type_slug = 'article' AND deleted_at IS NULL")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isZero();
        }
    }

    @Test
    void callerCountStartsAt1337() throws SQLException {
        try (Connection c = connect();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT value FROM counters WHERE key = 'caller_count'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getLong(1)).isEqualTo(1337L);
        }
    }

    @Test
    void allExpectedTablesExist() throws SQLException {
        Set<String> expected = Set.of(
                "users", "sessions", "login_attempts",
                "acl_grants",
                "roles", "user_roles",
                "door_state",
                "instance_features",
                "documents", "document_editors", "document_links",
                "document_revisions", "schemas",
                "message_bases", "threads", "posts", "thread_read",
                "chat_messages", "chat_rooms", "chat_room_members", "chat_room_messages",
                "activity_events", "reactions", "achievements", "user_achievements",
                "sysop_notes", "watch_list", "pending_content", "fortunes",
                "polls", "poll_options", "poll_votes",
                "oneliners", "netmail",
                "last_callers", "counters", "sysop_actions"
        );
        Set<String> actual = new HashSet<>();
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT table_name FROM information_schema.tables " +
                     "WHERE table_schema = 'public' AND table_type = 'BASE TABLE'");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) actual.add(rs.getString(1));
        }
        assertThat(actual).containsAll(expected);
    }

    @Test
    void handleCheckConstraintRejectsBadFormats() throws SQLException {
        try (Connection c = connect()) {
            // Valid handle is accepted
            try (Statement s = c.createStatement()) {
                s.executeUpdate("INSERT INTO users (handle, pw_hash) VALUES ('TRINITY', 'x')");
            }

            // Too short
            assertThatThrownBy(() -> {
                try (Statement s = c.createStatement()) {
                    s.executeUpdate("INSERT INTO users (handle, pw_hash) VALUES ('xx', 'x')");
                }
            }).hasMessageContaining("violates check constraint");

            // Contains a space
            assertThatThrownBy(() -> {
                try (Statement s = c.createStatement()) {
                    s.executeUpdate("INSERT INTO users (handle, pw_hash) VALUES ('bad name', 'x')");
                }
            }).hasMessageContaining("violates check constraint");

            // Too long (17 chars)
            assertThatThrownBy(() -> {
                try (Statement s = c.createStatement()) {
                    s.executeUpdate("INSERT INTO users (handle, pw_hash) VALUES ('aaaaaaaaaaaaaaaaa', 'x')");
                }
            }).hasMessageContaining("violates check constraint");
        }
    }

    @Test
    void sessionsCurrentScreenDefaultsToMenu() throws SQLException {
        try (Connection c = connect();
             Statement s = c.createStatement()) {
            s.executeUpdate("INSERT INTO users (handle, pw_hash) VALUES ('NEO', 'x')");
            s.executeUpdate("""
                INSERT INTO sessions (token, user_id, expires_at)
                SELECT 'tok-' || id, id, now() + interval '30 days'
                FROM users WHERE handle = 'NEO'
                """);
            try (ResultSet rs = s.executeQuery(
                    "SELECT current_screen->>'kind' FROM sessions WHERE token LIKE 'tok-%'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("menu");
            }
        }
    }

    @Test
    void onelinersLengthCheckRejectsOver70Chars() throws SQLException {
        try (Connection c = connect();
             Statement s = c.createStatement()) {
            s.executeUpdate("INSERT INTO users (handle, pw_hash) VALUES ('CYPHER', 'x')");
            int userId;
            try (ResultSet rs = s.executeQuery("SELECT id FROM users WHERE handle = 'CYPHER'")) {
                rs.next();
                userId = rs.getInt(1);
            }
            String tooLong = "x".repeat(71);
            assertThatThrownBy(() -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO oneliners (author_id, body) VALUES (?, ?)")) {
                    ps.setInt(1, userId);
                    ps.setString(2, tooLong);
                    ps.executeUpdate();
                }
            }).hasMessageContaining("violates check constraint");
        }
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }
}
