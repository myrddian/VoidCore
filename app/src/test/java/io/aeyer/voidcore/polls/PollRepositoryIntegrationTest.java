package io.aeyer.voidcore.polls;

import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for {@link PollRepository}. Boots Postgres 17,
 * runs all migrations including V8 (the polls migration), seeds a
 * couple of users, then exercises insert / list / vote / tally
 * paths end-to-end.
 */
@Testcontainers(disabledWithoutDocker = true)
class PollRepositoryIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("voidcore")
            .withUsername("postgres")
            .withPassword("postgres")
            .withInitScript("init-extensions.sql");

    static PollRepository repo;
    static long alice;
    static long bob;
    static long carol;

    @BeforeAll
    static void setup() throws SQLException {
        // V1..V5 first, then seed sysop (V6 needs it), then the rest.
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target("5")
                .load()
                .migrate();
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.executeUpdate(
                "INSERT INTO users (handle, pw_hash, is_sysop) " +
                "VALUES ('SYSOP', 'x', true)");
        }
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.executeUpdate(
                "INSERT INTO users (handle, pw_hash, is_sysop) VALUES ('ALICE','x',false)");
            try (ResultSet rs = s.executeQuery("SELECT id FROM users WHERE handle='ALICE'")) {
                rs.next(); alice = rs.getLong(1);
            }
            s.executeUpdate(
                "INSERT INTO users (handle, pw_hash, is_sysop) VALUES ('BOB','x',false)");
            try (ResultSet rs = s.executeQuery("SELECT id FROM users WHERE handle='BOB'")) {
                rs.next(); bob = rs.getLong(1);
            }
            s.executeUpdate(
                "INSERT INTO users (handle, pw_hash, is_sysop) VALUES ('CAROL','x',false)");
            try (ResultSet rs = s.executeQuery("SELECT id FROM users WHERE handle='CAROL'")) {
                rs.next(); carol = rs.getLong(1);
            }
        }

        DataSource ds = new SimpleDriverDataSource(
                loadDriver(), postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        DSLContext dsl = DSL.using(ds, SQLDialect.POSTGRES);
        repo = new PollRepository(dsl);
    }

    static Connection connect() throws SQLException {
        return DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    static Driver loadDriver() {
        try {
            return (Driver) Class.forName("org.postgresql.Driver")
                    .getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new IllegalStateException("Postgres driver missing", e);
        }
    }

    @Test
    void insertRequiresMinimumTwoOptions() {
        assertThatThrownBy(() ->
                repo.insert(alice, "lonely?", List.of("just one")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void insertReturnsIdAndPersistsOptionsInOrder() {
        long pid = repo.insert(alice, "favourite tracker?",
                List.of("ProTracker", "FastTracker", "Renoise"));
        var p = repo.findById(pid).orElseThrow();
        assertThat(p.authorHandle()).isEqualTo("ALICE");
        assertThat(p.isOpen()).isTrue();

        var tallies = repo.tallies(pid);
        assertThat(tallies).hasSize(3);
        assertThat(tallies).extracting(PollRepository.OptionTally::text)
                .containsExactly("ProTracker", "FastTracker", "Renoise");
        assertThat(tallies).allSatisfy(t -> assertThat(t.votes()).isZero());
    }

    @Test
    void voteCountsAndUserPickReflectLatestChoice() {
        long pid = repo.insert(alice, "amber or phosphor?",
                List.of("amber", "phosphor"));
        var tallies = repo.tallies(pid);
        long amberId = tallies.get(0).optionId();
        long phosphorId = tallies.get(1).optionId();

        repo.vote(pid, amberId, bob);
        repo.vote(pid, phosphorId, carol);
        repo.vote(pid, phosphorId, alice);

        // Bob switches to phosphor — single-choice replaces.
        repo.vote(pid, phosphorId, bob);

        assertThat(repo.userVoteOption(pid, bob)).contains(phosphorId);
        assertThat(repo.totalVotes(pid)).isEqualTo(3);

        var t = repo.tallies(pid);
        assertThat(t.get(0).votes()).isZero();   // amber: nobody now
        assertThat(t.get(1).votes()).isEqualTo(3); // phosphor: alice, bob, carol
    }

    @Test
    void closeMarksPollClosedIdempotently() {
        long pid = repo.insert(alice, "shipped?", List.of("yes", "no"));
        repo.close(pid);
        assertThat(repo.findById(pid).orElseThrow().isOpen()).isFalse();
        // Second close is a no-op (the WHERE clauses skips already-closed).
        repo.close(pid);
        assertThat(repo.findById(pid).orElseThrow().isOpen()).isFalse();
    }

    @Test
    void recentReturnsNewestFirst() {
        // Snapshot count before so this test is order-stable regardless
        // of how many polls earlier tests have created.
        int before = repo.recent(100).size();
        long a = repo.insert(alice, "first?", List.of("yes", "no"));
        long b = repo.insert(alice, "second?", List.of("yes", "no"));
        var listed = repo.recent(100);
        assertThat(listed.size()).isEqualTo(before + 2);
        // The two newest polls are the two we just inserted, with b
        // strictly more recent than a.
        assertThat(listed.get(0).id()).isEqualTo(b);
        assertThat(listed.get(1).id()).isEqualTo(a);
    }
}
