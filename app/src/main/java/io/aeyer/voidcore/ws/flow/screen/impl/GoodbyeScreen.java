package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.ws.flow.Frames;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.flow.screen.Screen;
import io.aeyer.voidcore.ws.flow.screen.Transition;
import io.aeyer.voidcore.ws.protocol.ServerMessage.InputPrompt;
import io.aeyer.voidcore.ws.flow.screen.ScreenComponent;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Terminal screen — the user has chosen [G]oodbye / logout. Paints
 * a goodbye frame on entry; the WS handler closes the connection
 * shortly after via {@code onAuthLogout}.
 *
 * <p>v1.4 PR-B: rendering moved out of {@code ScreenRouter} into this
 * class.
 *
 * <p>#93 (atmosphere bundle): one of several goodbye variants is
 * picked at random so the BBS doesn't feel scripted. The classic
 * {@code NO CARRIER} stays in rotation as a tribute.
 */
@ScreenComponent
public class GoodbyeScreen implements Screen {

    /** Variants are line-arrays so they can carry their own framing. */
    private static final List<List<String>> VARIANTS = List.of(
            List.of("", "+++", "", "NO CARRIER", ""),
            List.of("", "ATH0", "", "OK", "", "DISCONNECTED.", ""),
            List.of("", "session terminated.", "", "73 de sysop.", ""),
            List.of("", "carrier dropped.", "", "see you on the wire.", ""),
            List.of("", "Logout received.", "",
                    "Thanks for calling VOIDcore.", "")
    );

    @Override public Phase phase() { return Phase.GOODBYE; }
    @Override public String name() { return "goodbye"; }

    @Override
    public Transition onEnter(BbsContext ctx) {
        List<String> variant = VARIANTS.get(
                ThreadLocalRandom.current().nextInt(VARIANTS.size()));
        ctx.send(Frames.update("main", 99,
                Frames.textRows(variant, "bright_red")));
        ctx.send(new InputPrompt("none", null, null, null, null));
        return Transition.None.INSTANCE;
    }
}
