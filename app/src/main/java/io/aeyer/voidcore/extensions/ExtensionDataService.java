package io.aeyer.voidcore.extensions;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Stable service surface for extension-owned state.
 */
public class ExtensionDataService {

    private static final String GLOBAL_SCOPE = "global";
    private static final String USER_SCOPE = "user";

    private final ExtensionDataRepository repo;

    public ExtensionDataService(ExtensionDataRepository repo) {
        this.repo = repo;
    }

    public static ExtensionDataService disabled() {
        return new ExtensionDataService(null);
    }

    public Optional<JsonNode> getGlobal(String extensionSlug, String key) {
        if (repo == null) return Optional.empty();
        return repo.get(normalizeSlug(extensionSlug), GLOBAL_SCOPE, "", normalizeKey(key));
    }

    public Optional<JsonNode> getForUser(String extensionSlug, long userId, String key) {
        if (repo == null) return Optional.empty();
        return repo.get(normalizeSlug(extensionSlug), USER_SCOPE, Long.toString(userId), normalizeKey(key));
    }

    public void putGlobal(String extensionSlug, String key, JsonNode value) {
        if (repo == null) return;
        repo.put(normalizeSlug(extensionSlug), GLOBAL_SCOPE, "", normalizeKey(key), value);
    }

    public void putForUser(String extensionSlug, long userId, String key, JsonNode value) {
        if (repo == null) return;
        repo.put(normalizeSlug(extensionSlug), USER_SCOPE, Long.toString(userId), normalizeKey(key), value);
    }

    public void deleteGlobal(String extensionSlug, String key) {
        if (repo == null) return;
        repo.delete(normalizeSlug(extensionSlug), GLOBAL_SCOPE, "", normalizeKey(key));
    }

    public void deleteForUser(String extensionSlug, long userId, String key) {
        if (repo == null) return;
        repo.delete(normalizeSlug(extensionSlug), USER_SCOPE, Long.toString(userId), normalizeKey(key));
    }

    public List<String> globalKeys(String extensionSlug, String prefix, int limit) {
        if (repo == null) return List.of();
        return repo.keys(normalizeSlug(extensionSlug), GLOBAL_SCOPE, "", normalizePrefix(prefix), limit);
    }

    public List<String> userKeys(String extensionSlug, long userId, String prefix, int limit) {
        if (repo == null) return List.of();
        return repo.keys(normalizeSlug(extensionSlug), USER_SCOPE, Long.toString(userId),
                normalizePrefix(prefix), limit);
    }

    private static String normalizeSlug(String extensionSlug) {
        return normalizeToken(extensionSlug, "extensionSlug");
    }

    private static String normalizeKey(String key) {
        return normalizeToken(key, "key");
    }

    private static String normalizePrefix(String prefix) {
        if (prefix == null) return "";
        return prefix.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeToken(String value, String label) {
        if (value == null) throw new IllegalArgumentException(label + " must not be null");
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) throw new IllegalArgumentException(label + " must not be blank");
        return normalized;
    }
}
