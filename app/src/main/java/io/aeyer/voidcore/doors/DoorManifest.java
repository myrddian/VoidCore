package io.aeyer.voidcore.doors;

import java.util.List;

public record DoorManifest(
        String doorId,
        String name,
        String version,
        List<String> authors,
        String description,
        List<String> modesSupported,
        String defaultMode,
        Capabilities capabilities,
        int maxConcurrentSessions
) {

    public DoorManifest {
        authors = authors == null ? List.of() : List.copyOf(authors);
        modesSupported = modesSupported == null || modesSupported.isEmpty()
                ? List.of("normal")
                : List.copyOf(modesSupported);
        defaultMode = defaultMode == null || defaultMode.isBlank() ? "normal" : defaultMode;
        capabilities = capabilities == null ? Capabilities.defaults() : capabilities;
        maxConcurrentSessions = maxConcurrentSessions <= 0 ? 64 : maxConcurrentSessions;
    }

    public boolean supportsMode(String mode) {
        if (mode == null || mode.isBlank()) return false;
        return modesSupported.stream().anyMatch(mode::equalsIgnoreCase);
    }

    public record Capabilities(
            boolean storageKv,
            boolean llm,
            boolean notifications,
            boolean multiSession,
            boolean interSessionMessages,
            boolean userHandleVisible,
            boolean userIdVisible
    ) {
        public static Capabilities defaults() {
            return new Capabilities(false, false, false, false, false, true, true);
        }
    }
}
