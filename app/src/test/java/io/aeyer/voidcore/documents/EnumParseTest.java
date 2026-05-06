package io.aeyer.voidcore.documents;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the tolerant-parse contracts on the three
 * documents-substrate enums. Verifies the asymmetric forward-compat
 * rules per the design doc §3.2: Visibility defaults to PRIVATE on
 * null/unknown (safe failure); DocumentKind throws on null/unknown
 * (migration drift detection); Status throws on unknown but tolerates
 * null (column default).
 */
class EnumParseTest {

    @Test
    void documentKindParsesEachWireValue() {
        assertThat(DocumentKind.parse("howto")).isEqualTo(DocumentKind.HOWTO);
        assertThat(DocumentKind.parse("article")).isEqualTo(DocumentKind.ARTICLE);
        assertThat(DocumentKind.parse("link")).isEqualTo(DocumentKind.LINK);
        assertThat(DocumentKind.parse("glossary")).isEqualTo(DocumentKind.GLOSSARY);
        assertThat(DocumentKind.parse("release")).isEqualTo(DocumentKind.RELEASE);
        assertThat(DocumentKind.parse("note")).isEqualTo(DocumentKind.NOTE);
    }

    @Test
    void documentKindIsCaseInsensitive() {
        assertThat(DocumentKind.parse("HOWTO")).isEqualTo(DocumentKind.HOWTO);
        assertThat(DocumentKind.parse("Release")).isEqualTo(DocumentKind.RELEASE);
    }

    @Test
    void documentKindWireValuesAreLowercase() {
        assertThat(DocumentKind.HOWTO.wireValue()).isEqualTo("howto");
        assertThat(DocumentKind.RELEASE.wireValue()).isEqualTo("release");
    }

    @Test
    void documentKindThrowsOnNull() {
        assertThatThrownBy(() -> DocumentKind.parse(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void documentKindThrowsOnUnknown() {
        assertThatThrownBy(() -> DocumentKind.parse("dataset"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dataset");
    }

    @Test
    void visibilityParsesKnownWireValues() {
        assertThat(Visibility.parse("public")).isEqualTo(Visibility.PUBLIC);
        assertThat(Visibility.parse("private")).isEqualTo(Visibility.PRIVATE);
    }

    @Test
    void visibilityIsCaseInsensitive() {
        assertThat(Visibility.parse("PUBLIC")).isEqualTo(Visibility.PUBLIC);
    }

    @Test
    void visibilityNullDefaultsToPrivate() {
        assertThat(Visibility.parse(null)).isEqualTo(Visibility.PRIVATE);
    }

    @Test
    void visibilityUnknownDefaultsToPrivate() {
        // Forward-compat: a future "unlisted" or "members_only" should
        // not be exposed as public by an old binary.
        assertThat(Visibility.parse("unlisted")).isEqualTo(Visibility.PRIVATE);
        assertThat(Visibility.parse("members_only")).isEqualTo(Visibility.PRIVATE);
    }

    @Test
    void statusParsesKnownWireValues() {
        assertThat(Status.parse("draft")).isEqualTo(Status.DRAFT);
        assertThat(Status.parse("pending")).isEqualTo(Status.PENDING);
        assertThat(Status.parse("published")).isEqualTo(Status.PUBLISHED);
    }

    @Test
    void statusNullDefaultsToPublished() {
        assertThat(Status.parse(null)).isEqualTo(Status.PUBLISHED);
    }

    @Test
    void statusThrowsOnUnknown() {
        assertThatThrownBy(() -> Status.parse("archived"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("archived");
    }
}
