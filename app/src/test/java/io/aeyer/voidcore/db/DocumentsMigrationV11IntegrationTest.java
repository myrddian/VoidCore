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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the typed document-substrate migration applies cleanly on top of the
 * current repo's migration chain (V1..V10 bulletin ordering, then V11 typed
 * substrate). This is the repo-local equivalent of the plan's original "V10"
 * validation step.
 */
@Testcontainers(disabledWithoutDocker = true)
class DocumentsMigrationV11IntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("voidcore")
            .withUsername("postgres")
            .withPassword("postgres")
            .withInitScript("init-extensions.sql");

    static MigrateResult migrateResult;

    @BeforeAll
    static void migrate() throws SQLException {
        Flyway phase1 = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target("5")
                .load();
        phase1.migrate();

        try (Connection c = connect();
             Statement s = c.createStatement()) {
            int updated = s.executeUpdate(
                    "UPDATE users " +
                    "SET handle = 'SYSOP', pw_hash = 'x' " +
                    "WHERE pw_hash = 'BOOTSTRAP_SENTINEL'");
            if (updated == 0) {
                s.executeUpdate(
                        "INSERT INTO users (handle, pw_hash, is_sysop) " +
                        "VALUES ('SYSOP', 'x', true)");
            }

            s.executeUpdate(
                    "INSERT INTO bulletins (title, body, pinned) " +
                    "VALUES ('Welcome to VOIDcore', 'Hello world', true)");

            s.executeUpdate(
                    "INSERT INTO files (filename, title, size_bytes, uploader_id, nfo_text) " +
                    "SELECT 'PATTERN9.ZIP', 'Pattern Nine', 1234, id, 'NFO body' " +
                    "FROM users WHERE handle = 'SYSOP'");
        }

        Flyway flyway = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target("11")
                .load();
        migrateResult = flyway.migrate();
    }

    @Test
    void migrationsThroughV11Apply() {
        assertThat(migrateResult.success).isTrue();
        assertThat(migrateResult.migrations)
                .extracting(m -> m.version)
                .contains("11");
    }

    @Test
    void schemasTableIsSeededWithSixActiveBuiltins() throws SQLException {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT slug, version, status FROM schemas ORDER BY slug, version");
             ResultSet rs = ps.executeQuery()) {
            List<String> slugs = new ArrayList<>();
            while (rs.next()) {
                slugs.add(rs.getString("slug"));
                assertThat(rs.getInt("version")).isEqualTo(1);
                assertThat(rs.getString("status")).isEqualTo("active");
            }
            assertThat(slugs).containsExactly(
                    "article", "glossary", "howto", "link", "note", "release");
        }
    }

    @Test
    void documentsColumnsReflectTypedSubstrateShape() throws SQLException {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT column_name FROM information_schema.columns " +
                     "WHERE table_schema = 'public' " +
                     "  AND table_name = 'documents' " +
                     "  AND column_name IN ('kind', 'type_slug', 'type_version', 'rev', 'deleted_at', 'deleted_by')");
             ResultSet rs = ps.executeQuery()) {
            Set<String> cols = new HashSet<>();
            while (rs.next()) cols.add(rs.getString(1));
            assertThat(cols).containsExactlyInAnyOrder(
                    "type_slug", "type_version", "rev", "deleted_at", "deleted_by");
            assertThat(cols).doesNotContain("kind");
        }
    }

    @Test
    void everyDocumentReferencesASeededSchema() throws SQLException {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) " +
                     "FROM documents d " +
                     "LEFT JOIN schemas s " +
                     "  ON s.slug = d.type_slug AND s.version = d.type_version " +
                     "WHERE s.id IS NULL");
             ResultSet rs = ps.executeQuery()) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getLong(1)).isZero();
        }
    }

    @Test
    void backfilledDocumentsStartAtRevisionOneAndAreNotDeleted() throws SQLException {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT MIN(rev), MAX(rev), COUNT(*), " +
                     "       COUNT(*) FILTER (WHERE deleted_at IS NOT NULL), " +
                     "       COUNT(*) FILTER (WHERE type_slug = 'article'), " +
                     "       COUNT(*) FILTER (WHERE type_slug = 'release') " +
                     "FROM documents");
             ResultSet rs = ps.executeQuery()) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(1);
            assertThat(rs.getInt(2)).isEqualTo(1);
            assertThat(rs.getLong(3)).isEqualTo(2L);
            assertThat(rs.getLong(4)).isZero();
            assertThat(rs.getLong(5)).isEqualTo(1L);
            assertThat(rs.getLong(6)).isEqualTo(1L);
        }
    }

    @Test
    void freshMigrationProducesNoDocumentRevisionsYet() throws SQLException {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM document_revisions");
             ResultSet rs = ps.executeQuery()) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getLong(1)).isZero();
        }
    }

    @Test
    void typedDocumentIndexesExist() throws SQLException {
        Set<String> expected = Set.of(
                "documents_search", "documents_tags", "documents_type_slug",
                "documents_author", "documents_status", "documents_updated",
                "documents_live", "documents_deleted", "document_revisions_doc",
                "document_revisions_deletions", "schemas_slug_active");
        Set<String> actual = new HashSet<>();
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT indexname FROM pg_indexes WHERE schemaname = 'public'");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) actual.add(rs.getString(1));
        }
        assertThat(actual).containsAll(expected);
    }

    @Test
    void typedForeignKeysExistOnDocumentsAndRevisions() throws SQLException {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT conname " +
                     "FROM pg_constraint " +
                     "WHERE conrelid IN ('documents'::regclass, 'document_revisions'::regclass) " +
                     "  AND contype = 'f'");
             ResultSet rs = ps.executeQuery()) {
            Set<String> constraints = new HashSet<>();
            while (rs.next()) constraints.add(rs.getString(1));
            assertThat(constraints).contains("documents_type_fk", "document_revisions_type_fk");
        }
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }
}
