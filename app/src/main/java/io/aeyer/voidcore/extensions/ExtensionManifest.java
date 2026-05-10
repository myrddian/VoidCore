package io.aeyer.voidcore.extensions;

import java.util.List;

/**
 * JSON manifest for one instance-owned extension bundle under
 * {@code /instance/extensions/<slug>/voidcore-extension.json}.
 */
public record ExtensionManifest(String slug,
                                String label,
                                String version,
                                List<ExtensionScreenManifest> screens) {
}
