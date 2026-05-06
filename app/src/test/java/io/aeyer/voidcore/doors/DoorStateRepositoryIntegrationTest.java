package io.aeyer.voidcore.doors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.flywaydb.core.Flyway;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Driver;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class DoorStateRepositoryIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("voidcore")
            .withUsername("postgres")
            .withPassword("postgres")
            .withInitScript("init-extensions.sql");

    static DoorStateRepository repo;
    static final ObjectMapper json = new ObjectMapper();

    @BeforeAll
    static void setup() {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        DataSource ds = new SimpleDriverDataSource(loadDriver(),
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        repo = new DoorStateRepository(DSL.using(ds, SQLDialect.POSTGRES), json);
    }

    static Driver loadDriver() {
        try {
            return (Driver) Class.forName("org.postgresql.Driver")
                    .getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void putThenGetRoundTripsJsonAndVersion() {
        ObjectNode value = json.createObjectNode().put("room", "alley");

        DoorStateRepository.PutResult result = repo.put("cityline-mud", "user", "7", "room", value, null);

        assertThat(result.ok()).isTrue();
        var got = repo.get("cityline-mud", "user", "7", "room").orElseThrow();
        assertThat(got.value().get("room").asText()).isEqualTo("alley");
        assertThat(got.version()).isEqualTo(1L);
    }

    @Test
    void putConflictReturnsCurrentVersion() {
        repo.put("cityline-mud", "shared", "", "motd", json.createObjectNode().put("text", "one"), null);

        DoorStateRepository.PutResult conflict = repo.put(
                "cityline-mud", "shared", "", "motd",
                json.createObjectNode().put("text", "two"), 99L);

        assertThat(conflict.ok()).isFalse();
        assertThat(conflict.currentVersion()).isEqualTo(1L);
    }

    @Test
    void scanPagesAcrossPrefix() {
        repo.put("cityline-mud", "shared", "", "room:alley:1", json.createObjectNode().put("text", "a"), null);
        repo.put("cityline-mud", "shared", "", "room:alley:2", json.createObjectNode().put("text", "b"), null);
        repo.put("cityline-mud", "shared", "", "room:club:1", json.createObjectNode().put("text", "c"), null);

        DoorStateRepository.ScanPage page = repo.scan("cityline-mud", "shared", "", "room:alley:", null, 10);

        assertThat(page.entries()).extracting(DoorStateRepository.Entry::key)
                .containsExactly("room:alley:1", "room:alley:2");
        assertThat(page.cursor()).isNull();
    }

    @Test
    void deleteRemovesValue() {
        repo.put("cityline-mud", "global", "", "online", json.createObjectNode().put("count", 2), null);

        repo.delete("cityline-mud", "global", "", "online");

        assertThat(repo.get("cityline-mud", "global", "", "online")).isEmpty();
    }
}
