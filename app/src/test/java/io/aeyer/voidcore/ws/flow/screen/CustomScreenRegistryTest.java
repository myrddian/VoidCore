package io.aeyer.voidcore.ws.flow.screen;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.voidcore.instance.InstanceFeatureProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CustomScreenRegistryTest {

    @Test
    void duplicateCustomNamesAreCollapsed() {
        CustomScreenProvider first = provider("aeyer/releases");
        CustomScreenProvider second = provider("AEYER/RELEASES");

        CustomScreenRegistry registry = new CustomScreenRegistry(List.of(first, second));

        assertThat(registry.names()).containsExactly("aeyer/releases");
    }

    @Test
    void invalidNamesAreRejected() {
        CustomScreenRegistry registry = new CustomScreenRegistry(List.of(provider(""), provider("bad name")));

        assertThat(registry.names()).isEmpty();
    }

    @Test
    void createReturnsFreshScreensFromProvider() {
        CustomScreenRegistry registry = new CustomScreenRegistry(
                List.of(new CustomScreenProvider() {
                    int count = 0;

                    @Override public String screenName() { return "aeyer/releases"; }

                    @Override public Screen createScreen() {
                        count++;
                        return new TestCustomScreen(count);
                    }
                }));

        Screen first = registry.create("aeyer/releases").orElseThrow();
        Screen second = registry.create("aeyer/releases").orElseThrow();

        assertThat(first).isNotSameAs(second);
    }

    @Test
    void discoversManifestBackedScreensFromInstanceRoot(@TempDir Path tempDir) throws Exception {
        Path extDir = tempDir.resolve("extensions").resolve("aeyer");
        Files.createDirectories(extDir);
        Files.writeString(extDir.resolve("voidcore-extension.json"), """
                {
                  "slug": "aeyer",
                  "screens": [
                    {
                      "screenName": "aeyer/releases",
                      "label": "AEYER Releases",
                      "entrypoint": "releases.js"
                    }
                  ]
                }
                """);

        CustomScreenRegistry registry = new CustomScreenRegistry(
                List.of(),
                new ObjectMapper(),
                new InstanceFeatureProperties(List.of(), tempDir.toString()));

        assertThat(registry.names()).containsExactly("aeyer/releases");
        assertThat(registry.create("aeyer/releases")).isPresent();
    }

    @Test
    void builtInLookingNamesRemainLegalBecauseCustomRoutesUseSeparateIdentity(@TempDir Path tempDir) throws Exception {
        Path extDir = tempDir.resolve("extensions").resolve("aeyer");
        Files.createDirectories(extDir);
        Files.writeString(extDir.resolve("voidcore-extension.json"), """
                {
                  "slug": "aeyer",
                  "screens": [
                    { "screenName": "main-menu", "entrypoint": "menu.js" }
                  ]
                }
                """);

        CustomScreenRegistry registry = new CustomScreenRegistry(
                List.of(),
                new ObjectMapper(),
                new InstanceFeatureProperties(List.of(), tempDir.toString()));

        assertThat(registry.names()).containsExactly("main-menu");
    }

    private static CustomScreenProvider provider(String screenName) {
        return new CustomScreenProvider() {
            @Override public String screenName() { return screenName; }
            @Override public Screen createScreen() { return new TestCustomScreen(1); }
        };
    }

    private record TestCustomScreen(int id) implements Screen {
        @Override public Phase phase() { return Phase.MENU; }
        @Override public String name() { return "test-custom-" + id; }
        @Override public Transition onEnter(BbsContext ctx) { return Transition.None.INSTANCE; }
    }
}
