package io.aeyer.voidcore.extensions;

import java.nio.file.Path;
import java.util.List;

/**
 * Startup-discovered registration for one manifest-backed custom screen.
 */
public record ExtensionScreenRegistration(String extensionSlug,
                                          String extensionLabel,
                                          String extensionVersion,
                                          String screenName,
                                          String screenLabel,
                                          Path extensionRoot,
                                          Path manifestPath,
                                          String entrypoint,
                                          Path entrypointPath,
                                          List<String> capabilities,
                                          List<String> documentTypes) {

    public String displayLabel() {
        return screenLabel == null || screenLabel.isBlank()
                ? screenName
                : screenLabel.trim();
    }
}
