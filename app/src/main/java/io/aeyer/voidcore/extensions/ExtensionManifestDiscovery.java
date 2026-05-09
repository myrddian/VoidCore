package io.aeyer.voidcore.extensions;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.voidcore.instance.InstanceFeatureProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Startup discovery for extension manifests under the external instance root.
 *
 * <p>Invalid manifests are skipped with diagnostics rather than failing
 * startup so one bad extension cannot block the core engine.
 */
@Component
public class ExtensionManifestDiscovery {

    public static final String MANIFEST_FILENAME = "voidcore-extension.json";

    private static final Logger log = LoggerFactory.getLogger(ExtensionManifestDiscovery.class);

    private final ObjectMapper json;
    private final InstanceFeatureProperties instance;

    public ExtensionManifestDiscovery(ObjectMapper json, InstanceFeatureProperties instance) {
        this.json = json;
        this.instance = instance;
    }

    public List<ExtensionScreenRegistration> discover() {
        Path extensionsRoot = instance.extensionsRoot();
        if (!Files.isDirectory(extensionsRoot)) {
            return List.of();
        }
        try (var children = Files.list(extensionsRoot)) {
            return children.filter(Files::isDirectory)
                    .map(this::readManifest)
                    .flatMap(List::stream)
                    .toList();
        } catch (Exception e) {
            log.warn("failed scanning extension manifests under {}: {}",
                    extensionsRoot, e.toString());
            return List.of();
        }
    }

    private List<ExtensionScreenRegistration> readManifest(Path extensionDir) {
        Path manifestPath = extensionDir.resolve(MANIFEST_FILENAME);
        if (!Files.isRegularFile(manifestPath)) {
            return List.of();
        }
        try {
            ExtensionManifest manifest = json.readValue(manifestPath.toFile(), ExtensionManifest.class);
            if (manifest.screens() == null || manifest.screens().isEmpty()) {
                log.warn("extension manifest {} declares no screens", manifestPath);
                return List.of();
            }
            String slug = normalizeSlug(manifest.slug(), extensionDir.getFileName().toString());
            String label = normalizeLabel(manifest.label(), slug);
            String version = normalizeOptional(manifest.version());
            return manifest.screens().stream()
                    .filter(screen -> screen != null && screen.screenName() != null)
                    .map(screen -> toRegistration(slug, label, version, extensionDir, manifestPath, screen))
                    .toList();
        } catch (Exception e) {
            log.warn("failed reading extension manifest {}: {}",
                    manifestPath, e.toString());
            return List.of();
        }
    }

    private ExtensionScreenRegistration toRegistration(String slug,
                                                       String label,
                                                       String version,
                                                       Path extensionDir,
                                                       Path manifestPath,
                                                       ExtensionScreenManifest screen) {
        String entrypoint = normalizeOptional(screen.entrypoint());
        Path entrypointPath = entrypoint == null ? null : extensionDir.resolve(entrypoint).normalize();
        return new ExtensionScreenRegistration(
                slug,
                label,
                version,
                normalize(screen.screenName()),
                normalizeLabel(screen.label(), screen.screenName()),
                extensionDir,
                manifestPath,
                entrypoint,
                entrypointPath,
                copyOf(screen.capabilities()),
                copyOf(screen.documentTypes()));
    }

    private static List<String> copyOf(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static String normalizeSlug(String slug, String fallback) {
        String raw = normalizeOptional(slug);
        if (raw == null) raw = fallback;
        return normalize(raw.replace('\\', '/'));
    }

    private static String normalizeLabel(String label, String fallback) {
        String raw = normalizeOptional(label);
        return raw == null ? fallback : raw;
    }

    private static String normalizeOptional(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String normalize(String value) {
        if (value == null) throw new IllegalArgumentException("value must not be null");
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) throw new IllegalArgumentException("value must not be blank");
        return normalized;
    }
}
