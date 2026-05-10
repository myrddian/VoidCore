package io.aeyer.voidcore.extensions.host;

import io.aeyer.voidcore.ws.flow.layout.Element;

import java.util.List;

/**
 * UI surface exposed to extension-backed screens.
 */
public interface ExtensionUi {

    void banner(String label);

    void mainText(List<String> lines);

    void mainTree(Element tree, String focusPath);

    void promptNone();

    void promptKeystroke(String label, String validKeys);

    void promptLine(String label, Integer maxLength, String initial);

    void promptPassword(String label, Integer maxLength);

    void notifyMain(String text, String level, long durationMs);
}
