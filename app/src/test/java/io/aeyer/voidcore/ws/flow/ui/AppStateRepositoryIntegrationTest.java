package io.aeyer.voidcore.ws.flow.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.sql.*;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class AppStateRepositoryIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("voidcore")
            .withUsername("postgres")
            .withPassword("postgres")
            .withInitScript("init-extensions.sql");

    static AppStateRepository repo;
    static String sessionToken;
    static final ObjectMapper json = new ObjectMapper();

    @BeforeAll
    static void setup() throws SQLException {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        try (Connection c = connect();
             Statement s = c.createStatement()) {
            // V1 users_handle_check requires 3-16 chars from [A-Za-z0-9_\-.].
            s.executeUpdate("INSERT INTO users (handle, pw_hash, is_sysop) VALUES ('test-user','x',false)");
            try (ResultSet rs = s.executeQuery("SELECT id FROM users WHERE handle='test-user'")) {
                rs.next();
                long uid = rs.getLong(1);
                sessionToken = "T-" + uid;
                // sessions.expires_at is NOT NULL; pick a far-future date.
                s.executeUpdate(
                    "INSERT INTO sessions (token, user_id, expires_at) VALUES ('"
                        + sessionToken + "', " + uid + ", now() + interval '30 days')");
            }
        }
        DataSource ds = new SimpleDriverDataSource(loadDriver(),
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        repo = new AppStateRepository(DSL.using(ds, SQLDialect.POSTGRES), json);
    }

    static Connection connect() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(),
                postgres.getUsername(), postgres.getPassword());
    }

    static Driver loadDriver() {
        try { return (Driver) Class.forName("org.postgresql.Driver")
                .getDeclaredConstructor().newInstance(); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    @Test
    void readMissingKeyReturnsEmpty() {
        assertThat(repo.read(sessionToken, "doc:99")).isEmpty();
    }

    @Test
    void writeThenReadRoundTrips() {
        ObjectNode payload = json.createObjectNode().put("body_snapshot", "hello");
        repo.write(sessionToken, "doc:42", payload);
        Optional<ObjectNode> got = repo.read(sessionToken, "doc:42");
        assertThat(got).isPresent();
        assertThat(got.get().get("body_snapshot").asText()).isEqualTo("hello");
    }

    @Test
    void writeOverwritesExistingKeyOnly() {
        repo.write(sessionToken, "doc:42", json.createObjectNode().put("a", 1));
        repo.write(sessionToken, "doc:43", json.createObjectNode().put("b", 2));
        repo.write(sessionToken, "doc:42", json.createObjectNode().put("a", 99));
        assertThat(repo.read(sessionToken, "doc:42").get().get("a").asInt()).isEqualTo(99);
        assertThat(repo.read(sessionToken, "doc:43").get().get("b").asInt()).isEqualTo(2);
    }

    @Test
    void wipeRemovesOneKey() {
        repo.write(sessionToken, "doc:42", json.createObjectNode().put("a", 1));
        repo.wipe(sessionToken, "doc:42");
        assertThat(repo.read(sessionToken, "doc:42")).isEmpty();
    }
}
