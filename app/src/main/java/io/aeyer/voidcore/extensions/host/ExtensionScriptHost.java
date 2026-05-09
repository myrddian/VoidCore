package io.aeyer.voidcore.extensions.host;

import io.aeyer.voidcore.extensions.ExtensionScreenRegistration;

import java.util.Optional;

/**
 * Loader/factory for one extension runtime technology.
 */
public interface ExtensionScriptHost {

    Optional<ExtensionScript> createScript(ExtensionScreenRegistration registration);
}
