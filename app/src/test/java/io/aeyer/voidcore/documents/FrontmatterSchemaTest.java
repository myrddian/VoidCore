package io.aeyer.voidcore.documents;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FrontmatterSchemaTest {

    @Test
    void releaseHasSpecdFields() {
        List<FrontmatterField> f = FrontmatterSchema.fieldsFor(DocumentKind.RELEASE);
        assertThat(f).extracting(FrontmatterField::key)
                .containsExactly("artist", "year", "label",
                        "catalog_number", "genre", "external_url");
    }

    @Test
    void linkHasSpecdFields() {
        List<FrontmatterField> f = FrontmatterSchema.fieldsFor(DocumentKind.LINK);
        assertThat(f).extracting(FrontmatterField::key)
                .containsExactly("url", "summary", "source_kind");
    }

    @Test
    void articleHasSummaryAndPinned() {
        List<FrontmatterField> f = FrontmatterSchema.fieldsFor(DocumentKind.ARTICLE);
        assertThat(f).extracting(FrontmatterField::key)
                .containsExactly("summary", "pinned");
        assertThat(f.get(1).type()).isEqualTo(FrontmatterField.Type.BOOLEAN);
    }

    @Test
    void glossaryHasTermOnly() {
        // Term only in v1; see_also array deferred.
        List<FrontmatterField> f = FrontmatterSchema.fieldsFor(DocumentKind.GLOSSARY);
        assertThat(f).extracting(FrontmatterField::key)
                .containsExactly("term");
    }

    @Test
    void howtoHasSummaryAndOutcome() {
        // prerequisites array deferred.
        List<FrontmatterField> f = FrontmatterSchema.fieldsFor(DocumentKind.HOWTO);
        assertThat(f).extracting(FrontmatterField::key)
                .containsExactly("summary", "outcome");
    }

    @Test
    void noteHasSummaryOnly() {
        // anchor_doc_id deferred to ADR-024.
        List<FrontmatterField> f = FrontmatterSchema.fieldsFor(DocumentKind.NOTE);
        assertThat(f).extracting(FrontmatterField::key)
                .containsExactly("summary");
    }

    @Test
    void lettersUniquePerKindAndQReserved() {
        for (DocumentKind k : DocumentKind.values()) {
            Set<Character> seen = new HashSet<>();
            for (FrontmatterField f : FrontmatterSchema.fieldsFor(k)) {
                assertThat(f.letter())
                        .as("letter for %s/%s", k, f.key())
                        .isNotEqualTo('Q');
                assertThat(seen.add(f.letter()))
                        .as("letter %c on %s collides", f.letter(), k)
                        .isTrue();
            }
        }
    }

    @Test
    void keysUniquePerKind() {
        for (DocumentKind k : DocumentKind.values()) {
            Set<String> seen = new HashSet<>();
            for (FrontmatterField f : FrontmatterSchema.fieldsFor(k)) {
                assertThat(seen.add(f.key()))
                        .as("key %s on %s collides", f.key(), k)
                        .isTrue();
            }
        }
    }

    @Test
    void byLetterAndByKeyLookups() {
        assertThat(FrontmatterSchema.byLetter(DocumentKind.RELEASE, 'A'))
                .isPresent()
                .get().extracting(FrontmatterField::key)
                .isEqualTo("artist");
        assertThat(FrontmatterSchema.byLetter(DocumentKind.RELEASE, 'Z'))
                .isEmpty();

        assertThat(FrontmatterSchema.byKey(DocumentKind.RELEASE, "year"))
                .isPresent()
                .get().extracting(FrontmatterField::type)
                .isEqualTo(FrontmatterField.Type.INTEGER);
    }
}
