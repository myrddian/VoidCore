package io.aeyer.voidcore.extensions.host;

/**
 * Read-only session/user summary for extension-backed screens.
 */
public record ExtensionSessionView(boolean authenticated,
                                   Long userId,
                                   String handle,
                                   boolean sysop,
                                   String currentRoute) {
}
