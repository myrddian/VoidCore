package io.aeyer.voidcore.ws.flow.screen;

import io.aeyer.voidcore.ws.VoidCoreSession;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link BbsContext#bodyCanvasCols()} — the width prose bodies wrap to,
 * now that the client reports its viewport.
 */
class BbsContextBodyCanvasTest {

    private BbsContext ctxReporting(int cols) {
        VoidCoreSession session = mock(VoidCoreSession.class);
        when(session.viewportCols()).thenReturn(cols);
        return new BbsContext(session, null, null, null, null);
    }

    @Test
    void usesTheClientsReportedWidth() {
        assertThat(ctxReporting(100).bodyCanvasCols()).isEqualTo(100);
    }

    @Test
    void fallsBackToEightyWhenNothingHasBeenReported() {
        // A mocked session yields 0, as does any pre-report state. Screens
        // must keep their historical wrapping rather than collapsing.
        assertThat(ctxReporting(0).bodyCanvasCols()).isEqualTo(80);
    }

    @Test
    void capsVeryWideViewportsForReadability() {
        // A maximised 4K window would otherwise wrap prose at 300+ columns,
        // which the eye can't track back to the next line.
        assertThat(ctxReporting(400).bodyCanvasCols()).isEqualTo(120);
    }

    @Test
    void keepsAFloorOnVeryNarrowViewports() {
        assertThat(ctxReporting(24).bodyCanvasCols()).isEqualTo(40);
    }
}
