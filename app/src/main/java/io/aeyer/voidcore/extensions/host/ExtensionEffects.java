package io.aeyer.voidcore.extensions.host;

/**
 * Safe side effects available to extension-backed screens.
 */
public interface ExtensionEffects {

    void openUrl(String url);

    void setTheme(String name);

    void copyClipboard(String text);
}
