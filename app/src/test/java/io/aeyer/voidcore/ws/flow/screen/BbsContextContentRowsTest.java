package io.aeyer.voidcore.ws.flow.screen;

import io.aeyer.voidcore.ws.VoidCoreSession;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link BbsContext#contentRows()} and friends — how many rows of content a
 * screen may render, derived from the client's reported viewport height.
 */
class BbsContextContentRowsTest {

    private BbsContext ctxReporting(int rows) {
        VoidCoreSession session = mock(VoidCoreSession.class);
        when(session.viewportRows()).thenReturn(rows);
        return new BbsContext(session, null, null, null, null);
    }

    @Test
    void subtractsScreenChromeFromTheReportedHeight() {
        assertThat(ctxReporting(40).contentRows()).isEqualTo(34);
    }

    @Test
    void fallsBackToAClassicTerminalWhenNothingReported() {
        // A mocked session yields 0, as does any pre-report state.
        assertThat(ctxReporting(0).contentRows()).isEqualTo(24 - 6);
    }

    @Test
    void keepsAUsableFloorOnAShortViewport() {
        assertThat(ctxReporting(8).contentRows()).isEqualTo(5);
    }

    @Test
    void capsAbsurdlyTallViewports() {
        assertThat(ctxReporting(4000).contentRows()).isEqualTo(200);
    }

    @Test
    void neverReturnsFewerRowsThanTheScreenShowedBefore() {
        // The point of contentRowsAtLeast: a history list must not lose
        // scrollback it used to have just because the window is small.
        assertThat(ctxReporting(24).contentRowsAtLeast(40)).isEqualTo(40);
    }

    @Test
    void growsPastTheFloorWhenThereIsRoom() {
        assertThat(ctxReporting(80).contentRowsAtLeast(40)).isEqualTo(74);
    }

    @Test
    void staticFormMatchesTheInstanceForm() {
        // Paint paths holding only a session use the static form; they must
        // not drift from the context form.
        assertThat(BbsContext.contentRowsFor(40)).isEqualTo(ctxReporting(40).contentRows());
        assertThat(BbsContext.contentRowsFor(0)).isEqualTo(ctxReporting(0).contentRows());
    }
}
