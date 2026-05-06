package io.aeyer.voidcore.documents;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentSlugifierTest {

    @Test
    void simpleTitleLowercased() {
        assertThat(DocumentSlugifier.baseSlug("Pattern Nine"))
                .isEqualTo("pattern-nine");
    }

    @Test
    void nonAlnumCollapsedToSingleHyphen() {
        assertThat(DocumentSlugifier.baseSlug("Kick / Drum   Compression!"))
                .isEqualTo("kick-drum-compression");
    }

    @Test
    void leadingAndTrailingHyphensTrimmed() {
        assertThat(DocumentSlugifier.baseSlug("  ! Welcome ! "))
                .isEqualTo("welcome");
    }

    @Test
    void emptyOrAllPunctuationFallsBackToUntitled() {
        assertThat(DocumentSlugifier.baseSlug("")).isEqualTo("untitled");
        assertThat(DocumentSlugifier.baseSlug("   ")).isEqualTo("untitled");
        assertThat(DocumentSlugifier.baseSlug("!!!")).isEqualTo("untitled");
        assertThat(DocumentSlugifier.baseSlug(null)).isEqualTo("untitled");
    }

    @Test
    void unicodeCharactersDroppedNotPreserved() {
        // v1 simple: only [a-z0-9] survive; everything else becomes
        // a hyphen separator. Future polish could fold accents.
        assertThat(DocumentSlugifier.baseSlug("café 9"))
                .isEqualTo("caf-9");
    }

    @Test
    void slugifyReturnsBaseWhenNoCollision() {
        Set<String> taken = Set.of();
        assertThat(DocumentSlugifier.slugify("Hello World", taken::contains))
                .isEqualTo("hello-world");
    }

    @Test
    void slugifyAppendsCounterOnCollision() {
        Set<String> taken = new HashSet<>(Set.of("hello-world"));
        assertThat(DocumentSlugifier.slugify("Hello World", taken::contains))
                .isEqualTo("hello-world-2");
    }

    @Test
    void slugifyKeepsAdvancingPastMultipleCollisions() {
        Set<String> taken = new HashSet<>(Set.of(
                "hello-world", "hello-world-2", "hello-world-3"));
        assertThat(DocumentSlugifier.slugify("Hello World", taken::contains))
                .isEqualTo("hello-world-4");
    }

    @Test
    void slugifyOnEmptyTitleUsesUntitledThenSuffix() {
        Set<String> taken = new HashSet<>(Set.of("untitled"));
        assertThat(DocumentSlugifier.slugify("", taken::contains))
                .isEqualTo("untitled-2");
    }
}
