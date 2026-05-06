package io.aeyer.voidcore.documents;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for {@link DocumentRepository}. Boots Postgres 17,
 * runs the full current migration chain, seeds a sysop + a non-sysop, then
 * inserts test documents directly via SQL (skipping the V6 backfill
 * path which is already covered by
 * {@link io.aeyer.voidcore.db.DocumentsMigrationIntegrationTest}). Each
 * test method exercises one repo method against the seed data.
 */
@Testcontainers(disabledWithoutDocker = true)
class DocumentRepositoryIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("voidcore")
            .withUsername("postgres")
            .withPassword("postgres")
            .withInitScript("init-extensions.sql");

    static DocumentRepository repo;
    static long sysopId;
    static long nonSysopId;
    static long releaseDocId;
    static long articleDocId;
    static long pinnedArticleDocId;
    static long privateDocId;

    @BeforeAll
    static void setup() throws SQLException {
        // Apply the current migration chain so the repo and DB schema stay in
        // lockstep with the committed jOOQ model.
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(),
                            postgres.getUsername(),
                            postgres.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

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
                    "SELECT id FROM users WHERE handle='SYSOP'")) {
                rs.next(); sysopId = rs.getLong(1);
            }
            s.executeUpdate(
                "INSERT INTO users (handle, pw_hash, is_sysop) " +
                "VALUES ('CAPTAIN', 'x', false)");
            try (ResultSet rs = s.executeQuery(
                    "SELECT id FROM users WHERE handle='CAPTAIN'")) {
                rs.next(); nonSysopId = rs.getLong(1);
            }
        }

        // The full migration chain may leave zero or more document rows from
        // seed/compatibility migrations. The integration tests below assert
        // against an exact 4-doc fixture, so wipe any inherited state before
        // seeding.
        try (Connection c = connect();
             Statement s = c.createStatement()) {
            s.executeUpdate("DELETE FROM document_links");
            s.executeUpdate("DELETE FROM document_editors");
            s.executeUpdate("DELETE FROM document_revisions");
            s.executeUpdate("DELETE FROM documents");
        }

        // Seed test documents directly. Frontmatter shapes per spec.
        try (Connection c = connect()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO documents " +
                    "(slug, title, type_slug, body, frontmatter, tags, " +
                    " author_id, visibility, status, " +
                    " created_at, updated_at) " +
                    "VALUES (?, ?, ?, ?, ?::jsonb, '{}', ?, 'public', 'published', " +
                    " '2024-01-01T00:00:00Z'::timestamptz, '2024-01-01T00:00:00Z'::timestamptz) " +
                    "RETURNING id")) {
                ps.setString(1, "pattern9");
                ps.setString(2, "Pattern Nine");
                ps.setString(3, "release");
                ps.setString(4, "NFO body");
                ps.setString(5, "{\"filename\":\"PATTERN9.ZIP\",\"artist\":\"SYSOP\"}");
                ps.setLong(6, sysopId);
                try (ResultSet rs = ps.executeQuery()) { rs.next(); releaseDocId = rs.getLong(1); }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO documents " +
                    "(slug, title, type_slug, body, frontmatter, tags, " +
                    " author_id, visibility, status, " +
                    " created_at, updated_at) " +
                    "VALUES (?, ?, 'article', ?, '{\"pinned\":false}'::jsonb, '{}', ?, 'public', 'published', " +
                    " '2024-02-01T00:00:00Z'::timestamptz, '2024-02-01T00:00:00Z'::timestamptz) " +
                    "RETURNING id")) {
                ps.setString(1, "non-pinned-article");
                ps.setString(2, "Non-pinned article");
                ps.setString(3, "Body");
                ps.setLong(4, sysopId);
                try (ResultSet rs = ps.executeQuery()) { rs.next(); articleDocId = rs.getLong(1); }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO documents " +
                    "(slug, title, type_slug, body, frontmatter, tags, " +
                    " author_id, visibility, status, " +
                    " created_at, updated_at) " +
                    "VALUES (?, ?, 'article', ?, '{\"pinned\":true}'::jsonb, '{}', ?, 'public', 'published', " +
                    " '2024-03-01T00:00:00Z'::timestamptz, '2024-03-01T00:00:00Z'::timestamptz) " +
                    "RETURNING id")) {
                ps.setString(1, "pinned-welcome");
                ps.setString(2, "Welcome");
                ps.setString(3, "Pinned welcome body");
                ps.setLong(4, sysopId);
                try (ResultSet rs = ps.executeQuery()) { rs.next(); pinnedArticleDocId = rs.getLong(1); }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO documents " +
                    "(slug, title, type_slug, body, frontmatter, tags, " +
                    " author_id, visibility, status, " +
                    " created_at, updated_at) " +
                    "VALUES (?, ?, 'note', '', '{}'::jsonb, '{}', ?, 'private', 'published', " +
                    " '2024-04-01T00:00:00Z'::timestamptz, '2024-04-01T00:00:00Z'::timestamptz) " +
                    "RETURNING id")) {
                ps.setString(1, "captain-private");
                ps.setString(2, "Captain's private");
                ps.setLong(3, nonSysopId);
                try (ResultSet rs = ps.executeQuery()) { rs.next(); privateDocId = rs.getLong(1); }
            }
            // Add the sysop as an editor of the captain's private doc
            // so isEditor() can be tested.
            try (Statement s = c.createStatement()) {
                s.executeUpdate(
                    "INSERT INTO document_editors (document_id, user_id) " +
                    "VALUES (" + privateDocId + ", " + sysopId + ")");
            }
        }

        // Build the repo against the same DataSource.
        DataSource ds = new SimpleDriverDataSource(
                loadDriver(),
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword());
        DSLContext dsl = DSL.using(ds, SQLDialect.POSTGRES);
        repo = new DocumentRepository(dsl, new ObjectMapper());
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
            throw new IllegalStateException("Postgres driver missing on classpath", e);
        }
    }

    @Test
    void findByIdReturnsDocument() {
        Optional<DocumentRow> doc = repo.findById(releaseDocId);
        assertThat(doc).isPresent();
        assertThat(doc.get().slug()).isEqualTo("pattern9");
        assertThat(doc.get().kind()).isEqualTo(DocumentKind.RELEASE);
        assertThat(doc.get().visibility()).isEqualTo(Visibility.PUBLIC);
        assertThat(doc.get().status()).isEqualTo(Status.PUBLISHED);
    }

    @Test
    void findByIdMissingReturnsEmpty() {
        assertThat(repo.findById(999_999_999L)).isEmpty();
    }

    @Test
    void findBySlugReturnsDocument() {
        Optional<DocumentRow> doc = repo.findBySlug("pattern9");
        assertThat(doc).isPresent();
        assertThat(doc.get().id()).isEqualTo(releaseDocId);
    }

    @Test
    void findBySlugMissingReturnsEmpty() {
        assertThat(repo.findBySlug("nonexistent")).isEmpty();
    }

    @Test
    void findByFilenameOrSlugMatchesBySlug() {
        Optional<DocumentRow> doc = repo.findByFilenameOrSlug("pattern9");
        assertThat(doc).isPresent();
        assertThat(doc.get().slug()).isEqualTo("pattern9");
    }

    @Test
    void findByFilenameOrSlugMatchesByFilename() {
        // PATTERN9.ZIP lives in frontmatter.filename; lowered match.
        Optional<DocumentRow> doc = repo.findByFilenameOrSlug("PATTERN9.ZIP");
        assertThat(doc).isPresent();
        assertThat(doc.get().slug()).isEqualTo("pattern9");
    }

    @Test
    void listAllReturnsEveryDocOrderedByUpdatedDesc() {
        List<DocumentRow> docs = repo.listAll();
        assertThat(docs).hasSize(4);
        // Newest first: privateDocId (2024-04) > pinnedArticleDocId (2024-03)
        // > articleDocId (2024-02) > releaseDocId (2024-01).
        assertThat(docs.get(0).id()).isEqualTo(privateDocId);
        assertThat(docs.get(3).id()).isEqualTo(releaseDocId);
    }

    @Test
    void listByKindFiltersByKind() {
        List<DocumentRow> releases = repo.listByKind(DocumentKind.RELEASE);
        assertThat(releases).hasSize(1);
        assertThat(releases.get(0).slug()).isEqualTo("pattern9");

        List<DocumentRow> articles = repo.listByKind(DocumentKind.ARTICLE);
        assertThat(articles).hasSize(2);
    }

    @Test
    void listPinnedArticlesReturnsOnlyPinned() {
        List<DocumentRow> pinned = repo.listPinnedArticles();
        assertThat(pinned).hasSize(1);
        assertThat(pinned.get(0).slug()).isEqualTo("pinned-welcome");
        JsonNode fm = pinned.get(0).frontmatter();
        assertThat(fm.path("pinned").asBoolean()).isTrue();
    }

    @Test
    void isEditorTrueForEditor() {
        assertThat(repo.isEditor(privateDocId, sysopId)).isTrue();
    }

    @Test
    void isEditorFalseForNonEditor() {
        assertThat(repo.isEditor(privateDocId, nonSysopId)).isFalse();
        assertThat(repo.isEditor(releaseDocId, sysopId)).isFalse();
    }

    // === Write-path tests (v1.5 PR-4) =========================================
    //
    // Each write test creates an independent doc, exercises one or more
    // write paths, asserts, then deletes to leave the read-test fixture
    // counts intact. Tests don't share mutable state — they're safe under
    // any ordering.

    @Test
    void insertReturnsIdAndRoundTripsViaFindById() {
        ObjectNode fm = new ObjectMapper().createObjectNode()
                .put("summary", "roundtrip summary");
        long id = repo.insert(
                "wt-insert-roundtrip", "Insert RT", DocumentKind.NOTE,
                "body", fm, List.of("a", "b"), sysopId,
                Visibility.PUBLIC, Status.PUBLISHED);
        try {
            Optional<DocumentRow> doc = repo.findById(id);
            assertThat(doc).isPresent();
            assertThat(doc.get().slug()).isEqualTo("wt-insert-roundtrip");
            assertThat(doc.get().kind()).isEqualTo(DocumentKind.NOTE);
            assertThat(doc.get().tags()).containsExactly("a", "b");
            assertThat(doc.get().frontmatter().path("summary").asText())
                    .isEqualTo("roundtrip summary");
        } finally {
            repo.delete(id);
        }
    }

    @Test
    void updateTitleRoundTrips() {
        long id = repo.insert("wt-update-title", "Old Title",
                DocumentKind.NOTE, "body", new ObjectMapper().createObjectNode(),
                List.of(), sysopId, Visibility.PUBLIC, Status.PUBLISHED);
        try {
            repo.updateTitle(id, "New Title");
            assertThat(repo.findById(id).orElseThrow().title())
                    .isEqualTo("New Title");
        } finally {
            repo.delete(id);
        }
    }

    @Test
    void updateBodyRoundTrips() {
        long id = repo.insert("wt-update-body", "Title",
                DocumentKind.NOTE, "old body",
                new ObjectMapper().createObjectNode(),
                List.of(), sysopId, Visibility.PUBLIC, Status.PUBLISHED);
        try {
            repo.updateBody(id, "new body");
            assertThat(repo.findById(id).orElseThrow().body())
                    .isEqualTo("new body");
        } finally {
            repo.delete(id);
        }
    }

    @Test
    void updateTagsRoundTrips() {
        long id = repo.insert("wt-update-tags", "Title",
                DocumentKind.NOTE, "body",
                new ObjectMapper().createObjectNode(),
                List.of("old"), sysopId, Visibility.PUBLIC, Status.PUBLISHED);
        try {
            repo.updateTags(id, List.of("alpha", "beta", "gamma"));
            assertThat(repo.findById(id).orElseThrow().tags())
                    .containsExactly("alpha", "beta", "gamma");
        } finally {
            repo.delete(id);
        }
    }

    @Test
    void updateVisibilityRoundTrips() {
        long id = repo.insert("wt-update-vis", "Title",
                DocumentKind.NOTE, "body",
                new ObjectMapper().createObjectNode(),
                List.of(), sysopId, Visibility.PUBLIC, Status.PUBLISHED);
        try {
            repo.updateVisibility(id, Visibility.PRIVATE);
            assertThat(repo.findById(id).orElseThrow().visibility())
                    .isEqualTo(Visibility.PRIVATE);
        } finally {
            repo.delete(id);
        }
    }

    @Test
    void deleteRemovesTheRow() {
        long id = repo.insert("wt-delete-me", "Title",
                DocumentKind.NOTE, "body",
                new ObjectMapper().createObjectNode(),
                List.of(), sysopId, Visibility.PUBLIC, Status.PUBLISHED);
        repo.delete(id);
        assertThat(repo.findById(id)).isEmpty();
    }

    @Test
    void deleteSoftDeletesDocumentAndRetainsHistory() throws SQLException {
        long id = repo.insert("wt-delete-cascade", "Title",
                DocumentKind.NOTE, "body",
                new ObjectMapper().createObjectNode(),
                List.of(), sysopId, Visibility.PUBLIC, Status.PUBLISHED);
        try {
            repo.addEditor(id, nonSysopId);
            repo.recordRevision(id, "rev body",
                    new ObjectMapper().createObjectNode(), sysopId);

            assertThat(repo.isEditor(id, nonSysopId)).isTrue();

            repo.delete(id);

            assertThat(repo.findById(id)).isEmpty();
            DocumentRow deleted = repo.findByIdIncludingDeleted(id).orElseThrow();
            assertThat(deleted.isDeleted()).isTrue();

            try (Connection c = connect();
                 PreparedStatement ps = c.prepareStatement(
                         "SELECT count(*) FROM document_revisions WHERE document_id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getInt(1)).isEqualTo(2);
                }
            }
            try (Connection c = connect();
                 PreparedStatement ps = c.prepareStatement(
                         "SELECT count(*) FROM document_editors WHERE document_id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getInt(1)).isEqualTo(1);
                }
            }
        } finally {
            try (Connection c = connect();
                 PreparedStatement ps = c.prepareStatement(
                         "DELETE FROM documents WHERE id = ?")) {
                ps.setLong(1, id);
                ps.executeUpdate();
            }
        }
    }

    @Test
    void recordRevisionWritesARow() throws SQLException {
        long id = repo.insert("wt-record-rev", "Title",
                DocumentKind.NOTE, "body",
                new ObjectMapper().createObjectNode(),
                List.of(), sysopId, Visibility.PUBLIC, Status.PUBLISHED);
        try {
            repo.recordRevision(id, "rev body 1",
                    new ObjectMapper().createObjectNode(), sysopId);
            repo.recordRevision(id, "rev body 2",
                    new ObjectMapper().createObjectNode(), sysopId);

            try (Connection c = connect();
                 PreparedStatement ps = c.prepareStatement(
                         "SELECT body, edited_by FROM document_revisions " +
                         "WHERE document_id = ? ORDER BY edited_at ASC")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("body")).isEqualTo("rev body 1");
                    assertThat(rs.getLong("edited_by")).isEqualTo(sysopId);
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("body")).isEqualTo("rev body 2");
                }
            }
        } finally {
            repo.delete(id);
        }
    }

    @Test
    void addAndRemoveEditorRoundTripViaIsEditor() {
        long id = repo.insert("wt-editor-rt", "Title",
                DocumentKind.NOTE, "body",
                new ObjectMapper().createObjectNode(),
                List.of(), sysopId, Visibility.PUBLIC, Status.PUBLISHED);
        try {
            assertThat(repo.isEditor(id, nonSysopId)).isFalse();
            repo.addEditor(id, nonSysopId);
            assertThat(repo.isEditor(id, nonSysopId)).isTrue();

            // Idempotent — duplicate add doesn't throw.
            repo.addEditor(id, nonSysopId);
            assertThat(repo.isEditor(id, nonSysopId)).isTrue();

            repo.removeEditor(id, nonSysopId);
            assertThat(repo.isEditor(id, nonSysopId)).isFalse();
        } finally {
            repo.delete(id);
        }
    }

    // ===================================================================
    // PR-5 — faceted-nav read paths
    // ===================================================================

    @Test
    void findByFilterEmptyAsSysopReturnsAllSorted() {
        List<DocumentRow> docs = repo.findByFilter(
                DocumentFilter.empty(), sysopId, true, 0, 100);
        assertThat(docs).hasSize(4);
        // Newest first per updated_at DESC.
        assertThat(docs.get(0).id()).isEqualTo(privateDocId);
        assertThat(docs.get(3).id()).isEqualTo(releaseDocId);
    }

    @Test
    void findByFilterAsNonSysopHidesPrivateDocsOfOthers() {
        // sysop is also editor of privateDocId via the seed; pick a
        // user who's neither author, sysop, nor editor.
        long stranger = insertTempUser("STRANGER");
        try {
            List<DocumentRow> docs = repo.findByFilter(
                    DocumentFilter.empty(), stranger, false, 0, 100);
            // Three public docs visible; private one hidden.
            assertThat(docs).hasSize(3);
            assertThat(docs.stream().map(DocumentRow::id))
                    .doesNotContain(privateDocId);
        } finally {
            deleteTempUser(stranger);
        }
    }

    @Test
    void findByFilterAsAuthorSeesOwnPrivateDoc() {
        // nonSysopId authored privateDocId.
        List<DocumentRow> docs = repo.findByFilter(
                DocumentFilter.empty(), nonSysopId, false, 0, 100);
        assertThat(docs.stream().map(DocumentRow::id))
                .contains(privateDocId);
    }

    @Test
    void findByFilterPreAuthSeesPublicOnly() {
        List<DocumentRow> docs = repo.findByFilter(
                DocumentFilter.empty(), -1, false, 0, 100);
        assertThat(docs).hasSize(3);
        assertThat(docs.stream().map(DocumentRow::id))
                .doesNotContain(privateDocId);
    }

    @Test
    void findByFilterByKind() {
        List<DocumentRow> articles = repo.findByFilter(
                DocumentFilter.empty().withKind(DocumentKind.ARTICLE),
                sysopId, true, 0, 100);
        assertThat(articles).hasSize(2);
        assertThat(articles).allMatch(d -> d.kind() == DocumentKind.ARTICLE);
    }

    @Test
    void findByFilterByAuthor() {
        List<DocumentRow> mine = repo.findByFilter(
                DocumentFilter.empty().withAuthor(nonSysopId),
                sysopId, true, 0, 100);
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).id()).isEqualTo(privateDocId);
    }

    @Test
    void findByFilterByYearMatchesUpdatedAt() {
        List<DocumentRow> y2024 = repo.findByFilter(
                DocumentFilter.empty().withYear(2024),
                sysopId, true, 0, 100);
        // All four seed docs have updated_at in 2024.
        assertThat(y2024).hasSize(4);

        List<DocumentRow> y2099 = repo.findByFilter(
                DocumentFilter.empty().withYear(2099),
                sysopId, true, 0, 100);
        assertThat(y2099).isEmpty();
    }

    @Test
    void findByFilterIntersectionKindAndAuthor() {
        // sysop authored 3 docs (release, both articles); intersect
        // with kind=article should yield 2.
        List<DocumentRow> hit = repo.findByFilter(
                DocumentFilter.empty()
                        .withKind(DocumentKind.ARTICLE)
                        .withAuthor(sysopId),
                sysopId, true, 0, 100);
        assertThat(hit).hasSize(2);
    }

    @Test
    void findByFilterByTagIntersection() throws SQLException {
        // Tag the release with two tags; the article with one; query
        // by the intersection.
        try (Connection c = connect();
             Statement s = c.createStatement()) {
            s.executeUpdate(
                "UPDATE documents SET tags = '{industrial,vinyl}' " +
                "WHERE id = " + releaseDocId);
            s.executeUpdate(
                "UPDATE documents SET tags = '{industrial}' " +
                "WHERE id = " + articleDocId);
        }
        try {
            // Single tag — both rows match.
            List<DocumentRow> bothTagged = repo.findByFilter(
                    DocumentFilter.empty().withTag("industrial"),
                    sysopId, true, 0, 100);
            assertThat(bothTagged).hasSize(2);

            // Intersection of two tags — only the release.
            List<DocumentRow> onlyRelease = repo.findByFilter(
                    DocumentFilter.empty()
                            .withTag("industrial").withTag("vinyl"),
                    sysopId, true, 0, 100);
            assertThat(onlyRelease).hasSize(1);
            assertThat(onlyRelease.get(0).id()).isEqualTo(releaseDocId);
        } finally {
            // Clean tags so subsequent tests see the seed shape.
            try (Connection c = connect();
                 Statement s = c.createStatement()) {
                s.executeUpdate(
                    "UPDATE documents SET tags = '{}' " +
                    "WHERE id IN (" + releaseDocId + "," + articleDocId + ")");
            }
        }
    }

    @Test
    void findByFilterPagingOffsetLimit() {
        // 4 visible to sysop; page 1 (offset=0, limit=2) returns the
        // two newest; page 2 returns the two older ones.
        List<DocumentRow> page1 = repo.findByFilter(
                DocumentFilter.empty(), sysopId, true, 0, 2);
        List<DocumentRow> page2 = repo.findByFilter(
                DocumentFilter.empty(), sysopId, true, 2, 2);
        assertThat(page1).hasSize(2);
        assertThat(page2).hasSize(2);
        assertThat(page1.get(0).id()).isEqualTo(privateDocId);
        assertThat(page2.get(1).id()).isEqualTo(releaseDocId);
    }

    @Test
    void countByFilterMatchesFindCount() {
        long total = repo.countByFilter(DocumentFilter.empty(), sysopId, true);
        assertThat(total).isEqualTo(4);
        long articles = repo.countByFilter(
                DocumentFilter.empty().withKind(DocumentKind.ARTICLE),
                sysopId, true);
        assertThat(articles).isEqualTo(2);
    }

    @Test
    void kindFacetCountsReturnsPerKindCount() {
        Map<DocumentKind, Long> counts = repo.kindFacetCounts(
                DocumentFilter.empty(), sysopId, true);
        assertThat(counts.get(DocumentKind.RELEASE)).isEqualTo(1);
        assertThat(counts.get(DocumentKind.ARTICLE)).isEqualTo(2);
        assertThat(counts.get(DocumentKind.NOTE)).isEqualTo(1);
        // Non-existent kinds are absent (not zero).
        assertThat(counts).doesNotContainKey(DocumentKind.HOWTO);
    }

    @Test
    void authorFacetCountsReturnsHandleAndCount() {
        List<FacetCount.Author> authors = repo.authorFacetCounts(
                DocumentFilter.empty(), sysopId, true, 10);
        // Two authors: SYSOP (3 docs) and CAPTAIN (1 doc).
        assertThat(authors).hasSize(2);
        FacetCount.Author top = authors.get(0);
        assertThat(top.handle()).isEqualTo("SYSOP");
        assertThat(top.count()).isEqualTo(3);
    }

    @Test
    void whenFacetCountsReturnsYearBuckets() {
        List<FacetCount.Year> years = repo.whenFacetCounts(
                DocumentFilter.empty(), sysopId, true);
        // All seed docs are 2024.
        assertThat(years).hasSize(1);
        assertThat(years.get(0).year()).isEqualTo(2024);
        assertThat(years.get(0).count()).isEqualTo(4);
    }

    @Test
    void tagFacetCountsTopN() throws SQLException {
        try (Connection c = connect();
             Statement s = c.createStatement()) {
            s.executeUpdate(
                "UPDATE documents SET tags = '{a,b}' WHERE id = " + releaseDocId);
            s.executeUpdate(
                "UPDATE documents SET tags = '{a}' WHERE id = " + articleDocId);
        }
        try {
            List<FacetCount.Tag> tags = repo.tagFacetCounts(
                    DocumentFilter.empty(), sysopId, true, 10);
            // a appears in 2 docs, b in 1.
            assertThat(tags).hasSize(2);
            assertThat(tags.get(0).tag()).isEqualTo("a");
            assertThat(tags.get(0).count()).isEqualTo(2);
            assertThat(tags.get(1).tag()).isEqualTo("b");
            assertThat(tags.get(1).count()).isEqualTo(1);
        } finally {
            try (Connection c = connect();
                 Statement s = c.createStatement()) {
                s.executeUpdate(
                    "UPDATE documents SET tags = '{}' " +
                    "WHERE id IN (" + releaseDocId + "," + articleDocId + ")");
            }
        }
    }

    // ─── Helpers for visibility tests ─────────────────────────────────

    private static long insertTempUser(String handle) {
        try (Connection c = connect();
             Statement s = c.createStatement()) {
            s.executeUpdate("INSERT INTO users (handle, pw_hash, is_sysop) " +
                    "VALUES ('" + handle + "', 'x', false)");
            try (ResultSet rs = s.executeQuery(
                    "SELECT id FROM users WHERE handle='" + handle + "'")) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static void deleteTempUser(long id) {
        try (Connection c = connect();
             Statement s = c.createStatement()) {
            s.executeUpdate("DELETE FROM users WHERE id = " + id);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // ===================================================================
    // PR-6 — search + excludedTags + sort
    // ===================================================================

    @Test
    void findByFilterWithSearchHitsTitle() {
        // "Pattern Nine" is the release's title.
        List<DocumentRow> hits = repo.findByFilter(
                DocumentFilter.empty().withSearch("Pattern"),
                sysopId, true, 0, 100);
        assertThat(hits).extracting(DocumentRow::id).contains(releaseDocId);
    }

    @Test
    void findByFilterWithSearchHitsBody() {
        // "Pinned welcome body" is the pinned article's body.
        List<DocumentRow> hits = repo.findByFilter(
                DocumentFilter.empty().withSearch("welcome"),
                sysopId, true, 0, 100);
        assertThat(hits).extracting(DocumentRow::id).contains(pinnedArticleDocId);
    }

    @Test
    void findByFilterWithSearchAndKindIntersect() {
        List<DocumentRow> hits = repo.findByFilter(
                DocumentFilter.empty()
                        .withSearch("body")
                        .withKind(DocumentKind.ARTICLE),
                sysopId, true, 0, 100);
        // The pinned-article's body contains "body" — the release's
        // body is "NFO body" so it'd hit too if not for the kind filter.
        assertThat(hits).allMatch(d -> d.kind() == DocumentKind.ARTICLE);
    }

    @Test
    void findByFilterWithExcludedTagSubtracts() throws SQLException {
        try (Connection c = connect();
             Statement s = c.createStatement()) {
            s.executeUpdate(
                "UPDATE documents SET tags = '{a,beta}' WHERE id = " + releaseDocId);
            s.executeUpdate(
                "UPDATE documents SET tags = '{a}' WHERE id = " + articleDocId);
        }
        try {
            // tag:a -tag:beta → only articleDocId.
            List<DocumentRow> hits = repo.findByFilter(
                    DocumentFilter.empty()
                            .withTag("a").withExcludedTag("beta"),
                    sysopId, true, 0, 100);
            assertThat(hits).extracting(DocumentRow::id)
                    .contains(articleDocId)
                    .doesNotContain(releaseDocId);
        } finally {
            try (Connection c = connect();
                 Statement s = c.createStatement()) {
                s.executeUpdate(
                    "UPDATE documents SET tags = '{}' " +
                    "WHERE id IN (" + releaseDocId + "," + articleDocId + ")");
            }
        }
    }

    @Test
    void findByFilterSortAlphaOrdersByTitleAsc() {
        List<DocumentRow> docs = repo.findByFilter(
                DocumentFilter.empty(),
                sysopId, true, DocumentSort.ALPHA, 0, 100);
        // Titles: "Captain's private", "Non-pinned article",
        // "Pattern Nine", "Welcome".
        // ASC: Captain's private, Non-pinned article, Pattern Nine, Welcome.
        assertThat(docs.get(0).title()).startsWith("Captain");
        assertThat(docs.get(docs.size() - 1).title()).startsWith("Welcome");
    }

    @Test
    void findByFilterSortCreatedOrdersByCreatedDesc() {
        // All seed docs were inserted in the @BeforeAll block; created_at
        // is the schema default (now()) which means insert order. The
        // most-recent insert (privateDocId) comes first.
        List<DocumentRow> docs = repo.findByFilter(
                DocumentFilter.empty(),
                sysopId, true, DocumentSort.CREATED, 0, 100);
        assertThat(docs.get(0).id()).isEqualTo(privateDocId);
    }

    // ===================================================================
    // PR-7 — ~slug link graph + backlinks
    // ===================================================================

    @Test
    void insertWithBodyLinksWritesLinkRows() throws SQLException {
        long id = repo.insert("wt-link-source", "Title",
                DocumentKind.NOTE,
                "see ~pinned-welcome and ~non-pinned-article",
                new ObjectMapper().createObjectNode(),
                List.of(), sysopId, Visibility.PUBLIC, Status.PUBLISHED);
        try {
            try (Connection c = connect();
                 PreparedStatement ps = c.prepareStatement(
                         "SELECT target_id FROM document_links " +
                         "WHERE source_id = ? ORDER BY target_id")) {
                ps.setLong(1, id);
                List<Long> targets = new java.util.ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) targets.add(rs.getLong(1));
                }
                assertThat(targets).containsExactlyInAnyOrder(
                        pinnedArticleDocId, articleDocId);
            }
        } finally {
            repo.delete(id);
        }
    }

    @Test
    void updateBodyReplacesLinkSet() throws SQLException {
        long id = repo.insert("wt-link-update", "Title",
                DocumentKind.NOTE, "see ~pinned-welcome",
                new ObjectMapper().createObjectNode(),
                List.of(), sysopId, Visibility.PUBLIC, Status.PUBLISHED);
        try {
            // First save → 1 link.
            assertThat(linkTargetsFor(id)).containsExactly(pinnedArticleDocId);
            // Replace body → different link set.
            repo.updateBody(id, "now points at ~non-pinned-article instead");
            assertThat(linkTargetsFor(id)).containsExactly(articleDocId);
            // Empty body → no rows.
            repo.updateBody(id, "no slugs here just text");
            assertThat(linkTargetsFor(id)).isEmpty();
        } finally {
            repo.delete(id);
        }
    }

    @Test
    void unresolvedSlugSkippedSilently() throws SQLException {
        long id = repo.insert("wt-link-unresolved", "Title",
                DocumentKind.NOTE,
                "this references ~no-such-slug-anywhere which doesn't exist",
                new ObjectMapper().createObjectNode(),
                List.of(), sysopId, Visibility.PUBLIC, Status.PUBLISHED);
        try {
            assertThat(linkTargetsFor(id)).isEmpty();
        } finally {
            repo.delete(id);
        }
    }

    @Test
    void selfLinkSkipped() throws SQLException {
        long id = repo.insert("wt-self-ref", "Title",
                DocumentKind.NOTE, "I am ~wt-self-ref pointing at myself",
                new ObjectMapper().createObjectNode(),
                List.of(), sysopId, Visibility.PUBLIC, Status.PUBLISHED);
        try {
            assertThat(linkTargetsFor(id)).isEmpty();
        } finally {
            repo.delete(id);
        }
    }

    @Test
    void findBacklinksReturnsSourcesNewestFirst() throws SQLException {
        long src1 = repo.insert("wt-bl-src1", "Source 1",
                DocumentKind.NOTE, "see ~pinned-welcome",
                new ObjectMapper().createObjectNode(),
                List.of(), sysopId, Visibility.PUBLIC, Status.PUBLISHED);
        // Make src2 strictly newer (bump updated_at).
        long src2;
        try {
            src2 = repo.insert("wt-bl-src2", "Source 2",
                    DocumentKind.NOTE, "also see ~pinned-welcome",
                    new ObjectMapper().createObjectNode(),
                    List.of(), sysopId, Visibility.PUBLIC, Status.PUBLISHED);
            try {
                List<DocumentRow> backlinks = repo.findBacklinks(
                        pinnedArticleDocId, sysopId, true);
                assertThat(backlinks).extracting(DocumentRow::id)
                        .containsExactly(src2, src1); // newest first
            } finally {
                repo.delete(src2);
            }
        } finally {
            repo.delete(src1);
        }
    }

    @Test
    void findBacklinksRespectsVisibility() throws SQLException {
        long stranger = insertTempUser("BL_STRANGER");
        long privateSource = repo.insert("wt-bl-private", "Private",
                DocumentKind.NOTE, "private body referencing ~pinned-welcome",
                new ObjectMapper().createObjectNode(),
                List.of(), nonSysopId, Visibility.PRIVATE, Status.PUBLISHED);
        try {
            // Stranger can't see private docs of others — backlinks omits.
            List<DocumentRow> asStranger = repo.findBacklinks(
                    pinnedArticleDocId, stranger, false);
            assertThat(asStranger.stream().map(DocumentRow::id))
                    .doesNotContain(privateSource);
            // Sysop sees everything.
            List<DocumentRow> asSysop = repo.findBacklinks(
                    pinnedArticleDocId, sysopId, true);
            assertThat(asSysop.stream().map(DocumentRow::id))
                    .contains(privateSource);
        } finally {
            repo.delete(privateSource);
            deleteTempUser(stranger);
        }
    }

    @Test
    void findBacklinksEmptyWhenNoSources() {
        assertThat(repo.findBacklinks(releaseDocId, sysopId, true)).isEmpty();
    }

    private static List<Long> linkTargetsFor(long sourceId) {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT target_id FROM document_links " +
                     "WHERE source_id = ? ORDER BY target_id")) {
            ps.setLong(1, sourceId);
            List<Long> out = new java.util.ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getLong(1));
            }
            return out;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // ===================================================================
    // PR-4c — frontmatter field set / clear
    // ===================================================================

    @Test
    void setFrontmatterFieldStringRoundTrip() throws SQLException {
        long id = repo.insert("wt-fm-string", "Title",
                DocumentKind.NOTE, "body",
                new ObjectMapper().createObjectNode(),
                List.of(), sysopId, Visibility.PUBLIC, Status.PUBLISHED);
        try {
            repo.setFrontmatterField(id, "summary", "\"hello world\"");
            JsonNode fm = repo.findById(id).orElseThrow().frontmatter();
            assertThat(fm.path("summary").asText()).isEqualTo("hello world");
        } finally {
            repo.delete(id);
        }
    }

    @Test
    void setFrontmatterFieldIntegerRoundTrip() throws SQLException {
        long id = repo.insert("wt-fm-int", "Title",
                DocumentKind.RELEASE, "body",
                new ObjectMapper().createObjectNode().put("artist", "SYSOP"),
                List.of(), sysopId, Visibility.PUBLIC, Status.PUBLISHED);
        try {
            repo.setFrontmatterField(id, "year", "2024");
            JsonNode fm = repo.findById(id).orElseThrow().frontmatter();
            assertThat(fm.path("year").asInt()).isEqualTo(2024);
        } finally {
            repo.delete(id);
        }
    }

    @Test
    void setFrontmatterFieldBooleanRoundTrip() throws SQLException {
        long id = repo.insert("wt-fm-bool", "Title",
                DocumentKind.ARTICLE, "body",
                new ObjectMapper().createObjectNode(),
                List.of(), sysopId, Visibility.PUBLIC, Status.PUBLISHED);
        try {
            repo.setFrontmatterField(id, "pinned", "true");
            JsonNode fm = repo.findById(id).orElseThrow().frontmatter();
            assertThat(fm.path("pinned").asBoolean()).isTrue();
        } finally {
            repo.delete(id);
        }
    }

    @Test
    void clearFrontmatterFieldRemovesKey() throws SQLException {
        long id = repo.insert("wt-fm-clear", "Title",
                DocumentKind.RELEASE, "body",
                new ObjectMapper().createObjectNode().put("artist", "SYSOP"),
                List.of(), sysopId, Visibility.PUBLIC, Status.PUBLISHED);
        try {
            assertThat(repo.findById(id).orElseThrow()
                    .frontmatter().path("artist").asText()).isEqualTo("SYSOP");
            repo.clearFrontmatterField(id, "artist");
            assertThat(repo.findById(id).orElseThrow()
                    .frontmatter().has("artist")).isFalse();
        } finally {
            repo.delete(id);
        }
    }

    @Test
    void setFrontmatterFieldOverwritesExistingValue() throws SQLException {
        long id = repo.insert("wt-fm-overwrite", "Title",
                DocumentKind.RELEASE, "body",
                new ObjectMapper().createObjectNode().put("artist", "OLD"),
                List.of(), sysopId, Visibility.PUBLIC, Status.PUBLISHED);
        try {
            repo.setFrontmatterField(id, "artist", "\"NEW\"");
            JsonNode fm = repo.findById(id).orElseThrow().frontmatter();
            assertThat(fm.path("artist").asText()).isEqualTo("NEW");
        } finally {
            repo.delete(id);
        }
    }

    @Test
    void findByFilterSortMostLinkedRespectsLinkCounts() throws SQLException {
        // Seed 3 incoming links to articleDocId, 1 to releaseDocId.
        try (Connection c = connect();
             Statement s = c.createStatement()) {
            s.executeUpdate("INSERT INTO document_links (source_id, target_id, kind) " +
                    "VALUES (" + releaseDocId + ", " + articleDocId + ", 'reference')");
            s.executeUpdate("INSERT INTO document_links (source_id, target_id, kind) " +
                    "VALUES (" + pinnedArticleDocId + ", " + articleDocId + ", 'reference')");
            s.executeUpdate("INSERT INTO document_links (source_id, target_id, kind) " +
                    "VALUES (" + privateDocId + ", " + articleDocId + ", 'reference')");
            s.executeUpdate("INSERT INTO document_links (source_id, target_id, kind) " +
                    "VALUES (" + pinnedArticleDocId + ", " + releaseDocId + ", 'reference')");
        }
        try {
            List<DocumentRow> docs = repo.findByFilter(
                    DocumentFilter.empty(),
                    sysopId, true, DocumentSort.MOST_LINKED, 0, 100);
            // articleDocId (3 incoming) comes first, releaseDocId (1)
            // second; the two with zero incoming come after, in any order.
            assertThat(docs.get(0).id()).isEqualTo(articleDocId);
            assertThat(docs.get(1).id()).isEqualTo(releaseDocId);
        } finally {
            try (Connection c = connect();
                 Statement s = c.createStatement()) {
                s.executeUpdate("DELETE FROM document_links");
            }
        }
    }
}
