package io.aeyer.voidcore.instance;

import java.util.Map;

/**
 * Startup-loaded theme manifest from the external instance root.
 */
public record ThemeManifest(String name,
                            String label,
                            Map<String, String> variables,
                            ThemeVisualEffects effects) {
}
