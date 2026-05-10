package io.aeyer.voidcore.instance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Startup-loaded registry of built-in and overlay themes.
 */
@Component
public class ThemeRegistry {

    private static final Logger log = LoggerFactory.getLogger(ThemeRegistry.class);
    private static final Pattern VALID_THEME_NAME = Pattern.compile("[a-z0-9][a-z0-9._-]*");
    private static final List<String> BUILT_IN_NAMES = List.of("phosphor", "amber", "cga", "modern");
    private static final Set<String> ALLOWED_VARIABLES = Set.of(
            "--bg", "--fg", "--bright", "--black",
            "--dark-grey", "--grey",
            "--red", "--bright-red",
            "--green", "--bright-green",
            "--yellow", "--bright-yellow",
            "--blue", "--bright-blue",
            "--magenta", "--bright-magenta",
            "--cyan", "--bright-cyan",
            "--crt-glow-rgb",
            "--status-text-fg", "--status-key-fg", "--status-bracket-opacity",
            "--widget-editor-fg", "--widget-editor-line-num-fg",
            "--widget-editor-cursor-bg", "--widget-editor-cursor-fg",
            "--widget-mode-normal-bg", "--widget-mode-normal-fg",
            "--widget-mode-insert-bg", "--widget-mode-insert-fg",
            "--widget-mode-command-bg", "--widget-mode-command-fg",
            "--widget-mode-read-only-bg", "--widget-mode-read-only-fg",
            "--tok-heading-fg", "--tok-italic-fg", "--tok-italic-font-style",
            "--tok-code-fg", "--tok-fence-fg", "--tok-link-fg", "--tok-url-fg",
            "--tok-slug-fg", "--tok-bullet-fg",
            "--widget-text-field-outline",
            "--widget-text-field-cursor-bg", "--widget-text-field-cursor-fg",
            "--widget-header-title-fg", "--widget-header-right-opacity",
            "--widget-key-menu-key-fg", "--widget-key-menu-bracket-opacity",
            "--widget-key-menu-label-opacity"
    );

    private final List<String> themeNames;
    private final Map<String, ThemeManifest> overlayThemes;
    private final String overlayCss;

    @Autowired
    public ThemeRegistry(ObjectMapper json, InstanceFeatureProperties instance) {
        Map<String, ThemeManifest> overlays = discoverOverlayThemes(json, instance.themesRoot());
        LinkedHashSet<String> names = new LinkedHashSet<>(BUILT_IN_NAMES);
        names.addAll(overlays.keySet());
        this.themeNames = List.copyOf(names);
        this.overlayThemes = Map.copyOf(overlays);
        this.overlayCss = buildOverlayCss(overlays.values());
    }

    ThemeRegistry(List<String> themeNames, Map<String, ThemeManifest> overlayThemes) {
        this.themeNames = List.copyOf(themeNames);
        this.overlayThemes = Map.copyOf(overlayThemes);
        this.overlayCss = buildOverlayCss(overlayThemes.values());
    }

    public List<String> themeNames() {
        return themeNames;
    }

    public boolean isKnown(String themeName) {
        return themeNames.contains(normalize(themeName));
    }

    public String normalizeOrDefault(String themeName) {
        String normalized = normalize(themeName);
        return isKnown(normalized) ? normalized : "phosphor";
    }

    public String nextTheme(String currentTheme) {
        String normalized = normalizeOrDefault(currentTheme);
        int index = themeNames.indexOf(normalized);
        if (index < 0) return themeNames.getFirst();
        return themeNames.get((index + 1) % themeNames.size());
    }

    public Map<String, String> overlayThemeLabels() {
        LinkedHashMap<String, String> labels = new LinkedHashMap<>();
        for (ThemeManifest theme : overlayThemes.values()) {
            labels.put(theme.name(), theme.label() == null || theme.label().isBlank()
                    ? theme.name()
                    : theme.label().trim());
        }
        return Map.copyOf(labels);
    }

    public String overlayCss() {
        return overlayCss;
    }

    private static Map<String, ThemeManifest> discoverOverlayThemes(ObjectMapper json, Path themesRoot) {
        if (!Files.isDirectory(themesRoot)) {
            return Map.of();
        }
        LinkedHashMap<String, ThemeManifest> out = new LinkedHashMap<>();
        try (var files = Files.list(themesRoot)) {
            files.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .forEach(path -> readTheme(json, path).ifPresent(theme -> register(out, theme, path)));
        } catch (Exception e) {
            log.warn("failed scanning overlay themes under {}: {}", themesRoot, e.toString());
        }
        return Map.copyOf(out);
    }

    private static java.util.Optional<ThemeManifest> readTheme(ObjectMapper json, Path path) {
        try {
            ThemeManifest manifest = json.readValue(path.toFile(), ThemeManifest.class);
            if (manifest.name() == null || manifest.name().isBlank()) {
                log.warn("skipping theme {}: missing name", path);
                return java.util.Optional.empty();
            }
            String normalized = normalize(manifest.name());
            if (!VALID_THEME_NAME.matcher(normalized).matches()) {
                log.warn("skipping theme {}: invalid name {}", path, normalized);
                return java.util.Optional.empty();
            }
            Map<String, String> variables = sanitizeVariables(
                    manifest.variables() == null ? Map.of() : manifest.variables(),
                    path);
            return java.util.Optional.of(new ThemeManifest(
                    normalized,
                    manifest.label(),
                    variables,
                    manifest.effects()));
        } catch (Exception e) {
            log.warn("failed reading theme manifest {}: {}", path, e.toString());
            return java.util.Optional.empty();
        }
    }

    private static void register(Map<String, ThemeManifest> out, ThemeManifest theme, Path path) {
        if (BUILT_IN_NAMES.contains(theme.name())) {
            log.warn("skipping overlay theme {}: collides with built-in theme name", path);
            return;
        }
        if (out.containsKey(theme.name())) {
            log.warn("skipping duplicate overlay theme {}: {}", theme.name(), path);
            return;
        }
        out.put(theme.name(), theme);
    }

    private static String buildOverlayCss(Iterable<ThemeManifest> themes) {
        StringBuilder css = new StringBuilder();
        for (ThemeManifest theme : themes) {
            css.append("body[data-theme=\"").append(theme.name()).append("\"]{");
            for (Map.Entry<String, String> entry : theme.variables().entrySet()) {
                String key = normalizeCssVariable(entry.getKey());
                String value = entry.getValue();
                if (value == null || value.isBlank()) continue;
                css.append(key).append(':').append(value.trim()).append(';');
            }
            css.append('}');
            ThemeVisualEffects effects = theme.effects();
            if (effects != null) {
                if (Boolean.FALSE.equals(effects.scanlines())) {
                    css.append("body[data-theme=\"").append(theme.name())
                            .append("\"] .crt::before{display:none;}");
                }
                if (Boolean.FALSE.equals(effects.flicker())) {
                    css.append("body[data-theme=\"").append(theme.name())
                            .append("\"] .crt::after{animation:none;opacity:1;}");
                }
                if (Boolean.FALSE.equals(effects.noise())) {
                    css.append("body[data-theme=\"").append(theme.name())
                            .append("\"] .noise{display:none;}");
                }
            }
        }
        return css.toString();
    }

    private static Map<String, String> sanitizeVariables(Map<String, String> variables, Path path) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            String key = normalizeCssVariable(entry.getKey());
            if (!ALLOWED_VARIABLES.contains(key)) {
                log.warn("ignoring unsupported theme variable {} in {}", key, path);
                continue;
            }
            String value = entry.getValue();
            if (value == null || value.isBlank()) continue;
            out.put(key, value.trim());
        }
        return Map.copyOf(out);
    }

    private static String normalizeCssVariable(String key) {
        String trimmed = key == null ? "" : key.trim();
        if (trimmed.isEmpty()) return "--invalid";
        return trimmed.startsWith("--") ? trimmed : "--" + trimmed;
    }

    private static String normalize(String themeName) {
        if (themeName == null) return "phosphor";
        String normalized = themeName.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? "phosphor" : normalized;
    }
}
