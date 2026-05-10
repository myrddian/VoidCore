package io.aeyer.voidcore.extensions;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.voidcore.instance.InstanceFeatureProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ExtensionManifestDiscoveryTest {

    @Test
    void discoversRichScreenRegistrations(@TempDir Path tempDir) throws Exception {
        Path extDir = tempDir.resolve("extensions").resolve("aeyer");
        Files.createDirectories(extDir);
        Files.writeString(extDir.resolve("voidcore-extension.json"), """
                {
                  "slug": "aeyer",
                  "label": "AEYER Overlay",
                  "version": "0.1.0",
                  "screens": [
                    {
                      "screenName": "aeyer/releases",
                      "label": "AEYER Releases",
                      "entrypoint": "releases.js",
                      "capabilities": ["documents.read", "extensions_data.user"],
                      "documentTypes": ["release"]
                    }
                  ]
                }
                """);

        ExtensionManifestDiscovery discovery = new ExtensionManifestDiscovery(
                new ObjectMapper(),
                new InstanceFeatureProperties(java.util.List.of(), tempDir.toString()));

        var registrations = discovery.discover();

        assertThat(registrations).hasSize(1);
        ExtensionScreenRegistration registration = registrations.getFirst();
        assertThat(registration.extensionSlug()).isEqualTo("aeyer");
        assertThat(registration.extensionLabel()).isEqualTo("AEYER Overlay");
        assertThat(registration.extensionVersion()).isEqualTo("0.1.0");
        assertThat(registration.screenName()).isEqualTo("aeyer/releases");
        assertThat(registration.screenLabel()).isEqualTo("AEYER Releases");
        assertThat(registration.entrypoint()).isEqualTo("releases.js");
        assertThat(registration.entrypointPath()).isEqualTo(extDir.resolve("releases.js"));
        assertThat(registration.capabilities()).containsExactly("documents.read", "extensions_data.user");
        assertThat(registration.documentTypes()).containsExactly("release");
    }
}
