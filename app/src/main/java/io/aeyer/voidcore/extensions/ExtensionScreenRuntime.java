package io.aeyer.voidcore.extensions;

import io.aeyer.voidcore.ws.flow.screen.Screen;

/**
 * Runtime boundary for manifest-backed custom screens.
 *
 * <p>The first implementation may use GraalJS, but this interface keeps
 * manifest-backed screen registration engine-neutral.
 */
public interface ExtensionScreenRuntime {

    /**
     * Mint a fresh screen instance for one navigator stack layer.
     */
    Screen createScreen(ExtensionScreenRegistration registration);
}
