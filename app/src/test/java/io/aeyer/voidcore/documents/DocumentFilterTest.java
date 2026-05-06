package io.aeyer.voidcore.documents;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure value tests for {@link DocumentFilter}. Covers
 * {@code with*}/{@code drop*} mutators, canonical
 * {@link DocumentFilter#serialise} ordering, round-trip
 * {@link DocumentFilter#parse}, and breadcrumb display.
 */
class DocumentFilterTest {

    @Test
    void emptyIsEmpty() {
        DocumentFilter f = DocumentFilter.empty();
        assertThat(f.isEmpty()).isTrue();
        assertThat(f.serialise()).isEmpty();
    }

    @Test
    void withKindReplacesExisting() {
        DocumentFilter f = DocumentFilter.empty()
                .withKind(DocumentKind.HOWTO)
                .withKind(DocumentKind.NOTE);
        assertThat(f.kind()).contains(DocumentKind.NOTE);
    }

    @Test
    void withTagAccumulates() {
        DocumentFilter f = DocumentFilter.empty()
                .withTag("samples")
                .withTag("eurorack");
        assertThat(f.tagsList()).containsExactly("samples", "eurorack");
    }

    @Test
    void withTagDeDups() {
        DocumentFilter f = DocumentFilter.empty()
                .withTag("samples")
                .withTag("SAMPLES");          // case-insensitive
        assertThat(f.tagsList()).containsExactly("samples");
    }

    @Test
    void withTagIgnoresBlank() {
        DocumentFilter f = DocumentFilter.empty()
                .withTag("")
                .withTag("   ")
                .withTag(null);
        assertThat(f.isEmpty()).isTrue();
    }

    @Test
    void dropTagRemoves() {
        DocumentFilter f = DocumentFilter.empty()
                .withTag("a").withTag("b").withTag("c")
                .dropTag("b");
        assertThat(f.tagsList()).containsExactly("a", "c");
    }

    @Test
    void dropAllTagsClears() {
        DocumentFilter f = DocumentFilter.empty()
                .withTag("a").withTag("b")
                .dropAllTags();
        assertThat(f.tagsList()).isEmpty();
    }

    @Test
    void withYearOnly() {
        DocumentFilter f = DocumentFilter.empty().withYear(2024);
        assertThat(f.year()).contains(2024);
        assertThat(f.month()).isEmpty();
    }

    @Test
    void withYearMonthSetsBoth() {
        DocumentFilter f = DocumentFilter.empty().withYearMonth(2024, 10);
        assertThat(f.year()).contains(2024);
        assertThat(f.month()).contains(10);
    }

    @Test
    void withYearMonthRejectsBadMonth() {
        assertThatThrownBy(() -> DocumentFilter.empty().withYearMonth(2024, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DocumentFilter.empty().withYearMonth(2024, 13))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void withYearAfterYearMonthClearsMonth() {
        DocumentFilter f = DocumentFilter.empty()
                .withYearMonth(2024, 10)
                .withYear(2023);
        assertThat(f.year()).contains(2023);
        assertThat(f.month()).isEmpty();
    }

    // === Serialise ============================================================

    @Test
    void serialiseEmpty() {
        assertThat(DocumentFilter.empty().serialise()).isEmpty();
    }

    @Test
    void serialiseSingleFacet() {
        assertThat(DocumentFilter.empty().withKind(DocumentKind.HOWTO).serialise())
                .isEqualTo("kind=howto");
        assertThat(DocumentFilter.empty().withAuthor(42).serialise())
                .isEqualTo("by=42");
        assertThat(DocumentFilter.empty().withTag("samples").serialise())
                .isEqualTo("tag=samples");
        assertThat(DocumentFilter.empty().withYear(2024).serialise())
                .isEqualTo("when=2024");
        assertThat(DocumentFilter.empty().withYearMonth(2024, 7).serialise())
                .isEqualTo("when=2024-07");
    }

    @Test
    void serialiseAlphabeticalOrdering() {
        // kind=howto&tag=eurorack&tag=samples&by=42&when=2024
        // Canonical key order: by, kind, tag, when.
        DocumentFilter f = DocumentFilter.empty()
                .withYear(2024)
                .withTag("samples")
                .withKind(DocumentKind.HOWTO)
                .withAuthor(42)
                .withTag("eurorack");
        assertThat(f.serialise())
                .isEqualTo("by=42&kind=howto&tag=eurorack&tag=samples&when=2024");
    }

    @Test
    void serialiseSortsTagsAlphabetically() {
        // Tags added in user order zeta then alpha; canonical form
        // emits alpha then zeta even though tagsList() preserves
        // user order for breadcrumbs.
        DocumentFilter f = DocumentFilter.empty().withTag("zeta").withTag("alpha");
        assertThat(f.tagsList()).containsExactly("zeta", "alpha"); // user order preserved
        assertThat(f.serialise()).isEqualTo("tag=alpha&tag=zeta");
    }

    @Test
    void serialiseAndParseRoundTrip() {
        DocumentFilter f = DocumentFilter.empty()
                .withKind(DocumentKind.RELEASE)
                .withAuthor(100)
                .withTag("industrial")
                .withTag("eurorack")
                .withYearMonth(2024, 11);
        DocumentFilter round = DocumentFilter.parse(f.serialise());
        assertThat(round.serialise()).isEqualTo(f.serialise());
        assertThat(round.kind()).contains(DocumentKind.RELEASE);
        assertThat(round.authorId()).contains(100L);
        assertThat(round.tagsList()).containsExactlyInAnyOrder("industrial", "eurorack");
        assertThat(round.year()).contains(2024);
        assertThat(round.month()).contains(11);
    }

    // === Parse ================================================================

    @Test
    void parseEmpty() {
        assertThat(DocumentFilter.parse(null).isEmpty()).isTrue();
        assertThat(DocumentFilter.parse("").isEmpty()).isTrue();
        assertThat(DocumentFilter.parse("   ").isEmpty()).isTrue();
    }

    @Test
    void parseRejectsMalformed() {
        assertThatThrownBy(() -> DocumentFilter.parse("nokeyvalue"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DocumentFilter.parse("unknown=foo"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DocumentFilter.parse("by=notanumber"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DocumentFilter.parse("when=notadate"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parseToleratesUserOrdering() {
        // Input not in canonical order — output IS canonical.
        DocumentFilter f = DocumentFilter.parse(
                "tag=samples&kind=howto&by=42&tag=eurorack&when=2024-10");
        assertThat(f.serialise())
                .isEqualTo("by=42&kind=howto&tag=eurorack&tag=samples&when=2024-10");
    }

    @Test
    void breadcrumbReplacesAmpWithSlash() {
        DocumentFilter f = DocumentFilter.empty()
                .withKind(DocumentKind.HOWTO)
                .withTag("samples");
        assertThat(f.breadcrumb()).isEqualTo("kind=howto/tag=samples");
    }

    // === PR-6: search + excludedTags ==========================================

    @Test
    void withSearchSetsAndOverwrites() {
        DocumentFilter f = DocumentFilter.empty()
                .withSearch("kick drum")
                .withSearch("snare hit");
        assertThat(f.search()).contains("snare hit");
    }

    @Test
    void withSearchBlankClears() {
        DocumentFilter f = DocumentFilter.empty()
                .withSearch("kick").withSearch("");
        assertThat(f.search()).isEmpty();
    }

    @Test
    void withExcludedTagAccumulatesAndDeDups() {
        DocumentFilter f = DocumentFilter.empty()
                .withExcludedTag("beta")
                .withExcludedTag("BETA")          // case-insensitive
                .withExcludedTag("legacy");
        assertThat(f.excludedTagsList()).containsExactly("beta", "legacy");
    }

    @Test
    void serialiseSearchEncodesSpaces() {
        DocumentFilter f = DocumentFilter.empty().withSearch("kick drum");
        assertThat(f.serialise()).isEqualTo("search=kick%20drum");
    }

    @Test
    void serialiseExcludedTags() {
        DocumentFilter f = DocumentFilter.empty()
                .withExcludedTag("beta").withExcludedTag("legacy");
        assertThat(f.serialise()).isEqualTo("-tag=beta&-tag=legacy");
    }

    @Test
    void serialiseAndParseFullExpression() {
        DocumentFilter f = DocumentFilter.empty()
                .withKind(DocumentKind.HOWTO)
                .withTag("samples")
                .withExcludedTag("beta")
                .withSearch("kick drum")
                .withAuthor(42);
        DocumentFilter round = DocumentFilter.parse(f.serialise());
        assertThat(round.serialise()).isEqualTo(f.serialise());
        assertThat(round.search()).contains("kick drum");
        assertThat(round.excludedTagsList()).containsExactly("beta");
        assertThat(round.tagsList()).containsExactly("samples");
        assertThat(round.kind()).contains(DocumentKind.HOWTO);
        assertThat(round.authorId()).contains(42L);
    }

    @Test
    void parseExcludedTag() {
        DocumentFilter f = DocumentFilter.parse("-tag=beta&-tag=alpha");
        assertThat(f.excludedTagsList()).containsExactlyInAnyOrder("beta", "alpha");
    }

    @Test
    void emptyFilterStillEmptyAfterClearedSearch() {
        DocumentFilter f = DocumentFilter.empty().withSearch("foo").dropSearch();
        assertThat(f.isEmpty()).isTrue();
    }
}
