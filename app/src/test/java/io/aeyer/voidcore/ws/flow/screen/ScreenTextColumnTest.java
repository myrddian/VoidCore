package io.aeyer.voidcore.ws.flow.screen;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ScreenText#column(String, int)} — the fixed-width column helper.
 *
 * <p>Exists because {@code padRight} does not truncate (its Javadoc claimed
 * it did), so a handle longer than its column shunted every following span
 * rightwards and swallowed the separator: a 15-character handle printed
 * flush against the one-liner body in a 12-wide column.
 */
class ScreenTextColumnTest {

    @Test
    void padsShortValuesToTheColumnWidth() {
        assertThat(ScreenText.column("test", 10)).isEqualTo("test      ");
        assertThat(ScreenText.column("test", 10)).hasSize(10);
    }

    @Test
    void truncatesLongValuesWithAnEllipsis() {
        assertThat(ScreenText.column("BOOTSTRAP-SYSOP", 12)).isEqualTo("BOOTSTRAP-S…");
    }

    @Test
    void alwaysOccupiesExactlyTheColumnWidth() {
        // The property that matters: no input can move the next column.
        for (String s : new String[] {"", "a", "sysop", "BOOTSTRAP-SYSOP", "x".repeat(40)}) {
            assertThat(ScreenText.column(s, 17)).as("input %s", s).hasSize(17);
        }
    }

    @Test
    void treatsNullAsEmpty() {
        assertThat(ScreenText.column(null, 5)).isEqualTo("     ");
    }

    @Test
    void aMaximumLengthHandleStillLeavesASeparator() {
        // Handles are CHECK-constrained to 3..16 chars, and the screens use a
        // 17-wide column, so the longest legal handle never truncates and
        // never butts against the next span.
        String longest = "x".repeat(16);
        assertThat(ScreenText.column(longest, 17)).isEqualTo(longest + " ");
    }

    @Test
    void padRightStillDoesNotTruncate() {
        // Pinning the documented-vs-actual fix: padRight passes long values
        // through untouched, which is why column() exists.
        assertThat(ScreenText.padRight("BOOTSTRAP-SYSOP", 12)).isEqualTo("BOOTSTRAP-SYSOP");
    }
}
