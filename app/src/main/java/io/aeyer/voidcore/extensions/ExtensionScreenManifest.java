package io.aeyer.voidcore.extensions;

import java.util.List;

/**
 * One custom screen declared by an extension manifest.
 */
public record ExtensionScreenManifest(String screenName,
                                      String label,
                                      String entrypoint,
                                      List<String> capabilities,
                                      List<String> documentTypes) {
}
