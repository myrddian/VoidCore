package io.aeyer.voidcore.extensions.host;

import io.aeyer.voidcore.extensions.ExtensionScreenRegistration;

/**
 * Curated host-side context exposed to extension-backed custom screens.
 */
public interface ExtensionHostContext {

    ExtensionScreenRegistration registration();

    ExtensionUi ui();

    ExtensionNavigation navigation();

    ExtensionSessionView session();

    ExtensionDocuments documents();

    ExtensionDataAccess data();

    ExtensionEffects effects();
}
