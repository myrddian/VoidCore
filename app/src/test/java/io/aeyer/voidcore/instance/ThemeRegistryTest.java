package io.aeyer.voidcore.instance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ThemeRegistryTest {

    @TempDir
    Path tempDir;

    @Test
    void overlayThemesLoadAndParticipateInCycle() throws Exception {
        Path themesDir = tempDir.resolve("themes");
        Files.createDirectories(themesDir);
        Files.writeString(themesDir.resolve("solarized.json"), """
                {
                  "name": "solarized",
                  "label": "Solarized CRT",
                  "variables": {
                    "--bg": "#002b36",
                    "--status-text-fg": "#93a1a1"
                  },
                  "effects": {
                    "scanlines": false,
                    "noise": false
                  }
                }
                """);

        ThemeRegistry registry = new ThemeRegistry(
                new ObjectMapper(),
                new InstanceFeatureProperties(List.of(), tempDir.toString()));

        assertThat(registry.themeNames()).containsExactly(
                "phosphor", "amber", "cga", "modern", "solarized");
        assertThat(registry.isKnown("solarized")).isTrue();
        assertThat(registry.nextTheme("modern")).isEqualTo("solarized");
        assertThat(registry.overlayThemeLabels()).containsEntry("solarized", "Solarized CRT");
        assertThat(registry.overlayCss())
                .contains("body[data-theme=\"solarized\"]")
                .contains("--status-text-fg:#93a1a1;")
                .contains(".crt::before{display:none;}")
                .contains(".noise{display:none;}");
    }

    @Test
    void invalidOrBuiltInOverlayThemesAreIgnored() throws Exception {
        Path themesDir = tempDir.resolve("themes");
        Files.createDirectories(themesDir);
        Files.writeString(themesDir.resolve("bad.json"), """
                { "name": "bad theme", "variables": { "--bg": "#000000" } }
                """);
        Files.writeString(themesDir.resolve("modern.json"), """
                { "name": "modern", "variables": { "--bg": "#111111" } }
                """);

        ThemeRegistry registry = new ThemeRegistry(
                new ObjectMapper(),
                new InstanceFeatureProperties(List.of(), tempDir.toString()));

        assertThat(registry.themeNames()).containsExactly("phosphor", "amber", "cga", "modern");
        assertThat(registry.overlayCss()).isEmpty();
    }

    @Test
    void unsupportedThemeVariablesAreDropped() throws Exception {
        Path themesDir = tempDir.resolve("themes");
        Files.createDirectories(themesDir);
        Files.writeString(themesDir.resolve("ws360.json"), """
                {
                  "name": "ws360",
                  "variables": {
                    "--bg": "#071018",
                    "--definitely-not-real": "#ffffff"
                  }
                }
                """);

        ThemeRegistry registry = new ThemeRegistry(
                new ObjectMapper(),
                new InstanceFeatureProperties(List.of(), tempDir.toString()));

        assertThat(registry.overlayCss())
                .contains("--bg:#071018;")
                .doesNotContain("--definitely-not-real");
    }
}
