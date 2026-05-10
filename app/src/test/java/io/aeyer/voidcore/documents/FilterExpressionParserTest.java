package io.aeyer.voidcore.documents;

import io.aeyer.voidcore.auth.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Mockito unit tests for {@link FilterExpressionParser}. Covers the
 * grammar paths: bare words → search, key:value facets, {@code -tag:}
 * negation, by:handle resolution, lenient handling of unknown
 * facets / handles / when-formats.
 */
class FilterExpressionParserTest {

    UserRepository users;
    FilterExpressionParser parser;
    List<String> warnings;
    Consumer<String> notify;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        parser = new FilterExpressionParser(users);
        warnings = new ArrayList<>();
        notify = warnings::add;
    }

    @Test
    void emptyExpressionReturnsBaseUnchanged() {
        DocumentFilter base = DocumentFilter.empty().withTag("a");
        DocumentFilter out = parser.parse("", base, notify);
        assertThat(out).isEqualTo(base);
        out = parser.parse("   ", base, notify);
        assertThat(out).isEqualTo(base);
        out = parser.parse(null, base, notify);
        assertThat(out).isEqualTo(base);
    }

    @Test
    void bareWordsBecomeSearch() {
        DocumentFilter out = parser.parse("kick drum",
                DocumentFilter.empty(), notify);
        assertThat(out.search()).contains("kick drum");
    }

    @Test
    void kindFacetApplied() {
        DocumentFilter out = parser.parse("kind:howto",
                DocumentFilter.empty(), notify);
        assertThat(out.kind()).contains("howto");
    }

    @Test
    void multipleTagsAccumulate() {
        DocumentFilter out = parser.parse("tag:samples tag:eurorack",
                DocumentFilter.empty(), notify);
        assertThat(out.tagsList()).containsExactly("samples", "eurorack");
    }

    @Test
    void negatedTagExcludes() {
        DocumentFilter out = parser.parse("-tag:beta",
                DocumentFilter.empty(), notify);
        assertThat(out.excludedTagsList()).containsExactly("beta");
    }

    @Test
    void byNumericTreatedAsAuthorId() {
        DocumentFilter out = parser.parse("by:42",
                DocumentFilter.empty(), notify);
        assertThat(out.authorId()).contains(42L);
    }

    @Test
    void byHandleResolvesViaUserRepo() {
        when(users.findByHandle("SYSOP")).thenReturn(
                Optional.of(new UserRepository.UserRow(7, "SYSOP", "x", false, false)));

        DocumentFilter out = parser.parse("by:SYSOP",
                DocumentFilter.empty(), notify);

        assertThat(out.authorId()).contains(7L);
    }

    @Test
    void byHandleUnknownNotifiesAndSkips() {
        when(users.findByHandle("ghost")).thenReturn(Optional.empty());

        DocumentFilter out = parser.parse("by:ghost",
                DocumentFilter.empty(), notify);

        assertThat(out.authorId()).isEmpty();
        assertThat(warnings).anyMatch(w -> w.contains("user not found"));
    }

    @Test
    void whenYearOnly() {
        DocumentFilter out = parser.parse("when:2024",
                DocumentFilter.empty(), notify);
        assertThat(out.year()).contains(2024);
        assertThat(out.month()).isEmpty();
    }

    @Test
    void whenYearMonth() {
        DocumentFilter out = parser.parse("when:2024-10",
                DocumentFilter.empty(), notify);
        assertThat(out.year()).contains(2024);
        assertThat(out.month()).contains(10);
    }

    @Test
    void whenMalformedNotifiesAndSkips() {
        DocumentFilter out = parser.parse("when:notayear",
                DocumentFilter.empty(), notify);
        assertThat(out.year()).isEmpty();
        assertThat(warnings).anyMatch(w -> w.contains("bad when format"));
    }

    @Test
    void unknownFacetNotifiesAndSkips() {
        DocumentFilter out = parser.parse("foobar:baz",
                DocumentFilter.empty(), notify);
        assertThat(out).isEqualTo(DocumentFilter.empty());
        assertThat(warnings).anyMatch(w -> w.contains("unknown filter facet"));
    }

    @Test
    void mixedExpressionAppliesAllPieces() {
        when(users.findByHandle("SYSOP")).thenReturn(
                Optional.of(new UserRepository.UserRow(7, "SYSOP", "x", false, false)));

        DocumentFilter out = parser.parse(
                "kind:howto tag:samples by:SYSOP -tag:beta kick drum",
                DocumentFilter.empty(), notify);

        assertThat(out.kind()).contains("howto");
        assertThat(out.tagsList()).containsExactly("samples");
        assertThat(out.excludedTagsList()).containsExactly("beta");
        assertThat(out.authorId()).contains(7L);
        assertThat(out.search()).contains("kick drum");
    }

    @Test
    void valueLessTokenWarns() {
        DocumentFilter out = parser.parse("kind:",
                DocumentFilter.empty(), notify);
        assertThat(out.kind()).isEmpty();
        assertThat(warnings).anyMatch(w -> w.contains("no value"));
    }
}
