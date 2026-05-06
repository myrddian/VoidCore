package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.branding.BrandingProperties;
import io.aeyer.voidcore.presence.PresenceService;
import io.aeyer.voidcore.ws.flow.Banner;
import io.aeyer.voidcore.ws.flow.Frames;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.flow.screen.Screen;
import io.aeyer.voidcore.ws.flow.screen.ScreenComponent;
import io.aeyer.voidcore.ws.flow.screen.Transition;
import io.aeyer.voidcore.ws.protocol.ServerMessage.InputPrompt;
import io.aeyer.voidcore.ws.protocol.ServerMessage.Row;

import java.util.List;

/**
 * First half of the login flow — captures the handle. Pivots to the
 * password prompt on submit, or into the register flow if the user
 * types the magic word {@code "new"}.
 *
 * <p>Empty submission re-prompts (no error message — keep it quiet
 * the way classic BBSes did).
 *
 * <p>v1.4 PR-B: rendering moved out of {@code ScreenRouter} into this
 * class; the screen owns its initial paint and re-prompt.
 */
@ScreenComponent
public class LoginHandleScreen implements Screen {

    /**
     * The "or 'new' to register" hint surfaces self-service registration
     * per SPEC §13's "Visitor can register a handle, log in, …" without
     * forcing a separate keystroke menu. Typing the magic word "new"
     * pivots into the registration flow.
     */
    private static final InputPrompt PROMPT =
            new InputPrompt("line", "login (or 'new' to register):", 16, null, null);

    private final BrandingProperties branding;
    private final PresenceService presence;

    public LoginHandleScreen(BrandingProperties branding, PresenceService presence) {
        this.branding = branding;
        this.presence = presence;
    }

    @Override
    public Phase phase() {
        return Phase.LOGIN_HANDLE;
    }

    @Override
    public String name() {
        return "login-handle";
    }

    @Override
    public Transition onEnter(BbsContext ctx) {
        // Paint the banner + connect sequence into main on every entry.
        // This is what shows on the user's first connect AND on every path
        // that brings them back to LOGIN_HANDLE later (auto-reconnect after
        // a dropped WS, auth failure, logout) — without this paint, the
        // main region keeps whatever was on screen from the prior session
        // and the user sees a stale post-auth view alongside the login
        // prompt. ScreenRouter.onConnect previously owned this paint, but
        // the bounce-back-to-login paths skipped it. Owning the paint here
        // is the single-source-of-truth fix.
        paintConnectScreen(ctx);
        ctx.send(PROMPT);
        return Transition.None.INSTANCE;
    }

    @Override
    public Transition onCancel(BbsContext ctx) {
        // Esc from the handle prompt — quiet re-prompt, matching the
        // pre-extraction behaviour where the legacy default cancel
        // case reset to LOGIN_HANDLE and re-painted.
        ctx.send(PROMPT);
        return Transition.None.INSTANCE;
    }

    /**
     * Paints the banner + connect sequence (ATDT/CONNECT) into the
     * banner and main regions. The node number is the live count + 1
     * — same fiction as the legacy {@code ScreenRouter
     * .showConnectSequence}.
     */
    private void paintConnectScreen(BbsContext ctx) {
        ctx.send(Frames.update("banner", 1, Banner.rows()));
        int node = presence.activeCount() + 1;
        String syncLine = branding.displayFidoAddr().isBlank()
                ? "Synchronising packet exchange ................. offline    [..]"
                : "Synchronising with FidoNet " + branding.displayFidoAddr() + " ........... offline    [..]";
        List<Row> connect = Frames.textRows(List.of(
                "ATDT " + branding.displayDialNumber(),
                "CONNECT " + branding.displayConnectRate(),
                "",
                "Negotiating terminal capabilities ............. ANSI/CP437 [OK]",
                "Allocating node ............................... NODE "
                        + String.format("%02d", node) + "    [OK]",
                "Loading announcements ........................ 7 unread   [OK]",
                syncLine,
                ""
        ), "grey");
        ctx.send(Frames.update("main", 1, connect));
    }

    @Override
    public Transition onLine(BbsContext ctx, String text) {
        String handle = text == null ? "" : text.trim();
        if (handle.isEmpty()) {
            // Empty submit — quiet re-prompt.
            ctx.send(PROMPT);
            return Transition.None.INSTANCE;
        }
        if (handle.equalsIgnoreCase("new")) {
            // Pivot into the registration walk. AuthFinaliser owns the
            // draft init, framing paint, and navigator hand-off.
            ctx.services().authFinaliser().startRegisterFlow(ctx.session());
            return Transition.None.INSTANCE;
        }
        // Capture handle on the session, advance to password phase
        // via the navigator. LoginPasswordScreen.onEnter sends its
        // own prompt — no bridge needed.
        ctx.session().setPendingLoginHandle(handle);
        ctx.replaceTopAndEnter(Phase.LOGIN_PASSWORD);
        return Transition.None.INSTANCE;
    }
}
