package io.aeyer.voidcore.documents;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure value tests for {@link LinkGraphParser}. Covers the grammar
 * paths from the PR-7 design — bare slug, handle/slug, multiple in
 * one body, dedup, mid-word non-match, trailing punctuation.
 */
class LinkGraphParserTest {

    @Test
    void emptyBodyReturnsEmpty() {
        assertThat(LinkGraphParser.parse("")).isEmpty();
        assertThat(LinkGraphParser.parse(null)).isEmpty();
    }

    @Test
    void bareSlugMatches() {
        List<LinkGraphParser.Reference> refs = LinkGraphParser.parse(
                "see ~kick-drum-compression for context");
        assertThat(refs).hasSize(1);
        assertThat(refs.get(0).slug()).isEqualTo("kick-drum-compression");
        assertThat(refs.get(0).handle()).isNull();
    }

    @Test
    void handleSlugMatches() {
        List<LinkGraphParser.Reference> refs = LinkGraphParser.parse(
                "as ~sysop/pattern-nine demonstrates");
        assertThat(refs).hasSize(1);
        assertThat(refs.get(0).handle()).isEqualTo("sysop");
        assertThat(refs.get(0).slug()).isEqualTo("pattern-nine");
    }

    @Test
    void multipleSlugsInOneLine() {
        List<LinkGraphParser.Reference> refs = LinkGraphParser.parse(
                "compare ~kick-a with ~kick-b and ~sysop/snare");
        assertThat(refs).hasSize(3);
        assertThat(refs.get(0).slug()).isEqualTo("kick-a");
        assertThat(refs.get(1).slug()).isEqualTo("kick-b");
        assertThat(refs.get(2).slug()).isEqualTo("snare");
        assertThat(refs.get(2).handle()).isEqualTo("sysop");
    }

    @Test
    void dedupesBySlug() {
        List<LinkGraphParser.Reference> refs = LinkGraphParser.parse(
                "~kick-a then ~kick-a again — also ~kick-a");
        assertThat(refs).hasSize(1);
        assertThat(refs.get(0).slug()).isEqualTo("kick-a");
    }

    @Test
    void dedupesAcrossHandlePrefix() {
        // ~sysop/snare and ~snare resolve to the same slug — one row only.
        List<LinkGraphParser.Reference> refs = LinkGraphParser.parse(
                "~sysop/snare and later ~snare");
        assertThat(refs).hasSize(1);
        assertThat(refs.get(0).slug()).isEqualTo("snare");
    }

    @Test
    void noMatchInsideWord() {
        // A `~` preceded by a letter is not a reference start.
        List<LinkGraphParser.Reference> refs = LinkGraphParser.parse(
                "value~slug not a link");
        assertThat(refs).isEmpty();
    }

    @Test
    void matchesAtLineStart() {
        List<LinkGraphParser.Reference> refs = LinkGraphParser.parse(
                "~kick-a is line one\n~kick-b is line two");
        assertThat(refs).hasSize(2);
    }

    @Test
    void matchesAfterPunctuation() {
        List<LinkGraphParser.Reference> refs = LinkGraphParser.parse(
                "links: (~one), [~two], {~three}, ~four; ~five.");
        assertThat(refs).extracting(LinkGraphParser.Reference::slug)
                .containsExactly("one", "two", "three", "four", "five");
    }

    @Test
    void requiresLowercaseStart() {
        // Slug must start with [a-z]; uppercase or digit doesn't match.
        assertThat(LinkGraphParser.parse("~Slug")).isEmpty();
        assertThat(LinkGraphParser.parse("~9-slug")).isEmpty();
    }

    @Test
    void trailingPunctuationStripped() {
        List<LinkGraphParser.Reference> refs = LinkGraphParser.parse(
                "ending here ~slug-name. Period.");
        assertThat(refs).hasSize(1);
        assertThat(refs.get(0).slug()).isEqualTo("slug-name");
    }
}
