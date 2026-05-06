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

/**
 * Verifies V6__documents_substrate.sql applies cleanly against a real
 * Postgres 17 with a populated V1..V5 fixture (sysop user, bulletins,
 * files including V5 metadata + a slug-collision case + a NULL-uploader
 * case). Asserts schema shape, trigger registration, backfill row counts,
 * frontmatter round-trip, slug uniqueness, slug-collision dedupe,
 * sysop-fallback for NULL uploader_id, and that the legacy files /
 * bulletins tables still exist (drop is deferred to V-final).
 *
 * <p>Skips gracefully when no Docker daemon is reachable, matching
 * {@link FlywayMigrationIntegrationTest}. CI runs Docker so the test
 * executes there.
 *
 * <p>Strategy: apply V1..V5 first via Flyway with target=5, seed test
 * data (a sysop, a non-sysop, two bulletins, three files including a
 * slug-collision pair and a NULL-uploader case), then apply V6 by
 * re-running Flyway against the same DB. This mirrors the production
 * lifecycle where V6 lands against an already-running BBS with
 * bulletins + files in place.
 */
@Testcontainers(disabledWithoutDocker = true)
class DocumentsMigrationIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("voidcore")
            .withUsername("postgres")
            .withPassword("postgres")
            .withInitScript("init-extensions.sql");

    static long sysopId;
    static long bulletin1Id;
    static long bulletin2Id;
    static long fileZipId;
    static long fileRarId;
    static long fileNullUploaderId;

    @BeforeAll
    static void migrateAndSeed() throws SQLException {
        // Phase 1: apply V1..V5 only.
        Flyway phase1 = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target("5")
                .load();
        MigrateResult r1 = phase1.migrate();
        assertThat(r1.success).isTrue();

        // Phase 2: rename the bootstrap placeholder into SYSOP, then seed a
        // non-sysop, two bulletins, and three files
        // (one .ZIP and one .RAR sharing a base slug to test dedupe; one
        // with NULL uploader_id to test the sysop fallback).
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
            try (ResultSet rs = s.executeQuery(
                    "SELECT id FROM users WHERE handle = 'SYSOP'")) {
                rs.next();
                sysopId = rs.getLong(1);
            }

            s.executeUpdate(
                "INSERT INTO users (handle, pw_hash, is_sysop) " +
                "VALUES ('CAPTAIN', 'x', false)");

            // Bulletins.
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO bulletins (title, body, pinned) " +
                    "VALUES (?, ?, ?) RETURNING id")) {
                ps.setString(1, "Welcome to VOIDcore");
                ps.setString(2, "First bulletin body");
                ps.setBoolean(3, true);
                try (ResultSet rs = ps.executeQuery()) { rs.next(); bulletin1Id = rs.getLong(1); }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO bulletins (title, body, pinned) " +
                    "VALUES (?, ?, ?) RETURNING id")) {
                ps.setString(1, "Second notice");
                ps.setString(2, "Second bulletin body with EBM industrial keywords");
                ps.setBoolean(3, false);
                try (ResultSet rs = ps.executeQuery()) { rs.next(); bulletin2Id = rs.getLong(1); }
            }

            // Files: PATTERN9.ZIP and pattern9.RAR collide at slug "pattern9".
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO files (filename, title, size_bytes, uploader_id, " +
                    "                   nfo_text, external_url, year, artist, label, " +
                    "                   catalog_number, genre) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id")) {
                ps.setString(1, "PATTERN9.ZIP");
                ps.setString(2, "Pattern Nine");
                ps.setLong(3, 8_807_042L);
                ps.setObject(4, sysopId, java.sql.Types.BIGINT);
                ps.setString(5, "NFO body for pattern nine");
                ps.setString(6, "https://example.com/album/pattern-nine");
                ps.setObject(7, 2024, java.sql.Types.SMALLINT);
                ps.setString(8, "SYSOP");
                ps.setString(9, "Self-released");
                ps.setString(10, "SYSOP-009");
                ps.setString(11, "industrial");
                try (ResultSet rs = ps.executeQuery()) { rs.next(); fileZipId = rs.getLong(1); }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO files (filename, title, size_bytes, uploader_id, nfo_text) " +
                    "VALUES (?, ?, ?, ?, ?) RETURNING id")) {
                ps.setString(1, "pattern9.RAR");
                ps.setString(2, "Pattern Nine (RAR remix)");
                ps.setLong(3, 9_000_000L);
                ps.setObject(4, sysopId, java.sql.Types.BIGINT);
                ps.setString(5, "");
                try (ResultSet rs = ps.executeQuery()) { rs.next(); fileRarId = rs.getLong(1); }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO files (filename, title, size_bytes, uploader_id, nfo_text) " +
                    "VALUES (?, ?, ?, ?, ?) RETURNING id")) {
                ps.setString(1, "ORPHAN.ZIP");
                ps.setString(2, "Orphan release (no uploader)");
                ps.setLong(3, 1L);
                ps.setNull(4, java.sql.Types.BIGINT);
                ps.setString(5, "");
                try (ResultSet rs = ps.executeQuery()) { rs.next(); fileNullUploaderId = rs.getLong(1); }
            }
        }

        // Phase 3: apply V6 (target=6).
        Flyway phase2 = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target("6")
                .load();
        MigrateResult r2 = phase2.migrate();
        assertThat(r2.success).isTrue();
        assertThat(r2.migrations)
                .extracting(m -> m.version)
                .contains("6");
    }

    static Connection connect() throws SQLException {
        return DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    // === Tests ===============================================================

    @Test
    void migrationApplies() {
        // Smoke: @BeforeAll's success implies V1..V6 applied + fixture seeded.
        assertThat(sysopId).isPositive();
        assertThat(bulletin1Id).isPositive();
        assertThat(bulletin2Id).isPositive();
        assertThat(fileZipId).isPositive();
        assertThat(fileRarId).isPositive();
        assertThat(fileNullUploaderId).isPositive();
    }

    // --- Schema shape ---

    @Test
    void allDocumentsTablesExist() throws SQLException {
        Set<String> expected = Set.of(
                "documents", "document_editors",
                "document_links", "document_revisions");
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
    void documentsCheckConstraintsAreRegistered() throws SQLException {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT pg_get_constraintdef(con.oid) " +
                     "FROM pg_constraint con " +
                     "JOIN pg_class rel ON rel.oid = con.conrelid " +
                     "WHERE rel.relname = 'documents' AND con.contype = 'c'");
             ResultSet rs = ps.executeQuery()) {
            StringBuilder sb = new StringBuilder();
            while (rs.next()) sb.append(rs.getString(1)).append('\n');
            String constraints = sb.toString();
            assertThat(constraints).contains("kind");
            assertThat(constraints).contains("howto");
            assertThat(constraints).contains("release");
            assertThat(constraints).contains("visibility");
            assertThat(constraints).contains("public");
            assertThat(constraints).contains("private");
            assertThat(constraints).contains("status");
            assertThat(constraints).contains("draft");
            assertThat(constraints).contains("pending");
            assertThat(constraints).contains("published");
        }
    }

    @Test
    void searchVectorTriggerIsRegistered() throws SQLException {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT trigger_name FROM information_schema.triggers " +
                     "WHERE event_object_table = 'documents'");
             ResultSet rs = ps.executeQuery()) {
            Set<String> triggers = new HashSet<>();
            while (rs.next()) triggers.add(rs.getString(1));
            assertThat(triggers).contains("documents_search_vector");
        }
    }

    @Test
    void expectedIndexesExist() throws SQLException {
        Set<String> expected = Set.of(
                "documents_search", "documents_tags",
                "documents_kind", "documents_author",
                "documents_status", "documents_updated",
                "document_links_target", "document_revisions_doc");
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
    void legacyTablesStillExist() throws SQLException {
        // V6 explicitly does NOT drop files/bulletins. That's V-final's job,
        // landing at the end of the v1.5 milestone after consumers migrate.
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT table_name FROM information_schema.tables " +
                     "WHERE table_schema = 'public' " +
                     "  AND table_name IN ('files', 'bulletins')");
             ResultSet rs = ps.executeQuery()) {
            Set<String> still = new HashSet<>();
            while (rs.next()) still.add(rs.getString(1));
            assertThat(still).containsExactlyInAnyOrder("files", "bulletins");
        }
    }

    // --- Backfill row counts ---

    @Test
    void backfillProducesOneDocumentPerSourceRow() throws SQLException {
        try (Connection c = connect();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT " +
                     "  (SELECT count(*) FROM documents) AS doc_count, " +
                     "  (SELECT count(*) FROM files) AS file_count, " +
                     "  (SELECT count(*) FROM bulletins) AS bulletin_count")) {
            rs.next();
            long docs = rs.getLong("doc_count");
            long files = rs.getLong("file_count");
            long bulletins = rs.getLong("bulletin_count");
            assertThat(docs).isEqualTo(files + bulletins);
        }
    }

    @Test
    void backfillKindDistributionMatchesSourceTables() throws SQLException {
        try (Connection c = connect()) {
            long releaseCount;
            long fileCount;
            long articleCount;
            long bulletinCount;
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery(
                         "SELECT count(*) FROM documents WHERE kind = 'release'")) {
                rs.next(); releaseCount = rs.getLong(1);
            }
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT count(*) FROM files")) {
                rs.next(); fileCount = rs.getLong(1);
            }
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery(
                         "SELECT count(*) FROM documents WHERE kind = 'article'")) {
                rs.next(); articleCount = rs.getLong(1);
            }
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT count(*) FROM bulletins")) {
                rs.next(); bulletinCount = rs.getLong(1);
            }
            assertThat(releaseCount).isEqualTo(fileCount);
            assertThat(articleCount).isEqualTo(bulletinCount);
        }
    }

    @Test
    void everyBackfilledDocumentHasASearchVector() throws SQLException {
        try (Connection c = connect();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT count(*) FROM documents " +
                     "WHERE search_vector IS NULL OR search_vector::text = ''")) {
            rs.next();
            assertThat(rs.getLong(1)).isZero();
        }
    }

    @Test
    void slugsAreUniqueAcrossDocuments() throws SQLException {
        try (Connection c = connect();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT count(*) - count(DISTINCT slug) FROM documents")) {
            rs.next();
            assertThat(rs.getLong(1)).isZero();
        }
    }

    // --- Frontmatter round-trip / dedupe / fallback ---

    @Test
    void pinnedBulletinRoundTripsIntoFrontmatter() throws SQLException {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT frontmatter->>'pinned' FROM documents " +
                     "WHERE slug = ?")) {
            ps.setString(1, "bulletin-" + bulletin1Id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getString(1)).isEqualTo("true");
            }
        }
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT frontmatter->>'pinned' FROM documents " +
                     "WHERE slug = ?")) {
            ps.setString(1, "bulletin-" + bulletin2Id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getString(1)).isEqualTo("false");
            }
        }
    }

    @Test
    void fileV5MetadataRoundTripsIntoFrontmatter() throws SQLException {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT " +
                     "  frontmatter->>'filename' AS filename, " +
                     "  frontmatter->>'artist' AS artist, " +
                     "  frontmatter->>'year' AS year, " +
                     "  frontmatter->>'label' AS label, " +
                     "  frontmatter->>'catalog_number' AS catalog_number, " +
                     "  frontmatter->>'genre' AS genre, " +
                     "  frontmatter->>'external_url' AS external_url, " +
                     "  frontmatter->>'size_bytes' AS size_bytes " +
                     "FROM documents WHERE slug = 'pattern9'");
             ResultSet rs = ps.executeQuery()) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("filename")).isEqualTo("PATTERN9.ZIP");
            assertThat(rs.getString("artist")).isEqualTo("SYSOP");
            assertThat(rs.getString("year")).isEqualTo("2024");
            assertThat(rs.getString("label")).isEqualTo("Self-released");
            assertThat(rs.getString("catalog_number")).isEqualTo("SYSOP-009");
            assertThat(rs.getString("genre")).isEqualTo("industrial");
            assertThat(rs.getString("external_url"))
                    .isEqualTo("https://example.com/album/pattern-nine");
            assertThat(rs.getString("size_bytes")).isEqualTo("8807042");
        }
    }

    @Test
    void slugCollisionDedupesViaSuffix() throws SQLException {
        // PATTERN9.ZIP and pattern9.RAR collide at base slug "pattern9";
        // the deterministic dedupe (row_number ORDER BY id) gives id-asc
        // the bare slug and id-desc the "-2" suffix.
        Set<String> slugs = new HashSet<>();
        try (Connection c = connect();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT slug FROM documents " +
                     "WHERE kind = 'release' " +
                     "  AND (slug = 'pattern9' OR slug = 'pattern9-2')")) {
            while (rs.next()) slugs.add(rs.getString(1));
        }
        assertThat(slugs).containsExactlyInAnyOrder("pattern9", "pattern9-2");
    }

    @Test
    void nullUploaderFallsBackToSysopAuthor() throws SQLException {
        try (Connection c = connect();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT author_id FROM documents WHERE slug = 'orphan'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getLong(1)).isEqualTo(sysopId);
        }
    }

    @Test
    void bulletinAuthorFallsBackToSysop() throws SQLException {
        // Bulletins have no author_id column; backfill assigns the sysop.
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT author_id FROM documents WHERE kind = 'article'");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                assertThat(rs.getLong(1)).isEqualTo(sysopId);
            }
        }
    }
}
