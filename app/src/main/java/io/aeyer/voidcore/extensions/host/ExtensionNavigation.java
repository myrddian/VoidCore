package io.aeyer.voidcore.extensions.host;

import io.aeyer.voidcore.ws.flow.screen.Phase;

/**
 * Navigation actions available to extension-backed screens.
 */
public interface ExtensionNavigation {

    void pop();

    void pushCustom(String screenName);

    void pushCore(Phase phase);
}
