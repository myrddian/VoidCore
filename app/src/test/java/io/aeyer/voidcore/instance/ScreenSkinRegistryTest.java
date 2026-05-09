package io.aeyer.voidcore.instance;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.voidcore.ws.flow.Frames;
import io.aeyer.voidcore.ws.protocol.ServerMessage.Row;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ScreenSkinRegistryTest {

    @TempDir
    Path tempDir;

    @Test
    void rendersOverlayBannerAndMainSlotsFromAnsiAssets() throws Exception {
        Path skinDir = tempDir.resolve("skins").resolve("login");
        Files.createDirectories(skinDir);
        Files.writeString(skinDir.resolve("voidcore-skin.json"), """
                {
                  "screenName": "login-handle",
                  "banner": { "asset": "banner.ans" },
                  "main": {
                    "asset": "main.ans",
                    "slots": [
                      { "name": "body", "row": 2, "col": 4, "width": 12, "height": 2 }
                    ]
                  }
                }
                """);
        Files.writeString(skinDir.resolve("banner.ans"), "\u001b[35mSKIN BANNER\u001b[0m\n");
        Files.writeString(skinDir.resolve("main.ans"), """
                +------------------+
                |                  |
                |                  |
                +------------------+
                """);

        ScreenSkinRegistry registry = new ScreenSkinRegistry(
                new ObjectMapper(),
                new InstanceFeatureProperties(List.of(), tempDir.toString()));

        List<Row> banner = registry.renderBannerOrDefault("login-handle", List.of());
        List<Row> main = registry.renderMainOrDefault(
                "login-handle",
                List.of(),
                Map.of("body", Frames.textRows(List.of("CONNECT 9600", "NODE 05"), "grey")));

        assertThat(banner.getFirst().spans().getFirst().text()).isEqualTo("SKIN BANNER");
        assertThat(banner.getFirst().spans().getFirst().fg()).isEqualTo("magenta");
        assertThat(textOf(main.get(1))).contains("CONNECT 9600");
        assertThat(textOf(main.get(2))).contains("NODE 05");
    }

    @Test
    void fallsBackWhenSkinManifestIsInvalid() throws Exception {
        Path skinDir = tempDir.resolve("skins").resolve("broken");
        Files.createDirectories(skinDir);
        Files.writeString(skinDir.resolve("voidcore-skin.json"), """
                {
                  "screenName": "main-menu",
                  "banner": { "asset": "missing.ans" }
                }
                """);
        List<Row> fallback = Frames.textRows(List.of("fallback"), "default");

        ScreenSkinRegistry registry = new ScreenSkinRegistry(
                new ObjectMapper(),
                new InstanceFeatureProperties(List.of(), tempDir.toString()));

        assertThat(registry.renderBannerOrDefault("main-menu", fallback)).isEqualTo(fallback);
    }

    @Test
    void wrapsTreeScreensWithShellChromeWhenConfigured() throws Exception {
        Path skinDir = tempDir.resolve("skins").resolve("tree");
        Files.createDirectories(skinDir);
        Files.writeString(skinDir.resolve("voidcore-skin.json"), """
                {
                  "screenName": "ws360/demo",
                  "tree": {
                    "variant": "console",
                    "headerTitle": "WS/360 PRESENTATION SHELL",
                    "headerRightAnnotation": "TREE MODE",
                    "paddingLeft": 2,
                    "topAssets": ["top.ans", "top2.ans"],
                    "leftAssets": ["left.ans", "left2.ans"],
                    "rightAssets": ["right.ans", "right2.ans"],
                    "bottomAssets": ["bottom.ans", "bottom2.ans"],
                    "footerText": "footer",
                    "footerStyle": "bright_cyan"
                  }
                }
                """);
        Files.writeString(skinDir.resolve("top.ans"), "TOP\n");
        Files.writeString(skinDir.resolve("top2.ans"), "TOP2\n");
        Files.writeString(skinDir.resolve("left.ans"), "LEFT\n");
        Files.writeString(skinDir.resolve("left2.ans"), "LEFT2\n");
        Files.writeString(skinDir.resolve("right.ans"), "RIGHT\n");
        Files.writeString(skinDir.resolve("right2.ans"), "RIGHT2\n");
        Files.writeString(skinDir.resolve("bottom.ans"), "BOTTOM\n");
        Files.writeString(skinDir.resolve("bottom2.ans"), "BOTTOM2\n");

        ScreenSkinRegistry registry = new ScreenSkinRegistry(
                new ObjectMapper(),
                new InstanceFeatureProperties(List.of(), tempDir.toString()));

        io.aeyer.voidcore.ws.flow.layout.Element wrapped = registry.renderTreeOrDefault(
                "ws360/demo",
                new io.aeyer.voidcore.ws.flow.layout.Element.Text("body", "default"));

        assertThat(wrapped).isInstanceOf(io.aeyer.voidcore.ws.flow.layout.Element.Shell.class);
        io.aeyer.voidcore.ws.flow.layout.Element.Shell shell =
                (io.aeyer.voidcore.ws.flow.layout.Element.Shell) wrapped;
        assertThat(shell.top()).isInstanceOf(io.aeyer.voidcore.ws.flow.layout.Element.VStack.class);
        assertThat(shell.left()).isInstanceOf(io.aeyer.voidcore.ws.flow.layout.Element.VStack.class);
        assertThat(shell.body()).isInstanceOf(io.aeyer.voidcore.ws.flow.layout.Element.Padded.class);
        assertThat(shell.right()).isInstanceOf(io.aeyer.voidcore.ws.flow.layout.Element.VStack.class);
        assertThat(shell.bottom()).isInstanceOf(io.aeyer.voidcore.ws.flow.layout.Element.VStack.class);
    }

    @Test
    void wildcardSkinAppliesToArbitraryScreenNames() throws Exception {
        Path skinDir = tempDir.resolve("skins").resolve("global");
        Files.createDirectories(skinDir);
        Files.writeString(skinDir.resolve("voidcore-skin.json"), """
                {
                  "includeScreens": ["*"],
                  "banner": { "asset": "banner.ans" },
                  "main": {
                    "asset": "main.ans",
                    "slots": [
                      { "name": "body", "row": 2, "col": 3, "width": 10, "height": 1 }
                    ]
                  }
                }
                """);
        Files.writeString(skinDir.resolve("banner.ans"), "GLOBAL BANNER\n");
        Files.writeString(skinDir.resolve("main.ans"), """
                +-----------+
                |           |
                +-----------+
                """);

        ScreenSkinRegistry registry = new ScreenSkinRegistry(
                new ObjectMapper(),
                new InstanceFeatureProperties(List.of(), tempDir.toString()));

        List<Row> banner = registry.renderBannerOrDefault("threads-list", List.of());
        List<Row> main = registry.renderMainOrDefault(
                "threads-list",
                List.of(),
                Map.of("body", Frames.textRows(List.of("LIVE BODY"), "grey")));

        assertThat(textOf(banner.getFirst())).contains("GLOBAL BANNER");
        assertThat(textOf(main.get(1))).contains("LIVE BODY");
    }

    @Test
    void exactSkinOverridesWildcardSkin() throws Exception {
        Path wildcardDir = tempDir.resolve("skins").resolve("global");
        Files.createDirectories(wildcardDir);
        Files.writeString(wildcardDir.resolve("voidcore-skin.json"), """
                {
                  "includeScreens": ["*"],
                  "banner": { "asset": "banner.ans" }
                }
                """);
        Files.writeString(wildcardDir.resolve("banner.ans"), "GLOBAL\n");

        Path exactDir = tempDir.resolve("skins").resolve("exact");
        Files.createDirectories(exactDir);
        Files.writeString(exactDir.resolve("voidcore-skin.json"), """
                {
                  "screenName": "threads-list",
                  "banner": { "asset": "banner.ans" }
                }
                """);
        Files.writeString(exactDir.resolve("banner.ans"), "EXACT\n");

        ScreenSkinRegistry registry = new ScreenSkinRegistry(
                new ObjectMapper(),
                new InstanceFeatureProperties(List.of(), tempDir.toString()));

        List<Row> banner = registry.renderBannerOrDefault("threads-list", List.of());
        assertThat(textOf(banner.getFirst())).contains("EXACT");
    }

    @Test
    void resolvesBannerPolicyFromMatchingSkin() throws Exception {
        Path skinDir = tempDir.resolve("skins").resolve("global");
        Files.createDirectories(skinDir);
        Files.writeString(skinDir.resolve("voidcore-skin.json"), """
                {
                  "includeScreens": ["*"],
                  "bannerPolicy": "always_compact",
                  "banner": { "asset": "banner.ans" }
                }
                """);
        Files.writeString(skinDir.resolve("banner.ans"), "GLOBAL\n");

        ScreenSkinRegistry registry = new ScreenSkinRegistry(
                new ObjectMapper(),
                new InstanceFeatureProperties(List.of(), tempDir.toString()));

        assertThat(registry.bannerPolicyFor("threads-list").wireValue()).isEqualTo("always_compact");
        assertThat(registry.bannerPolicyFor("missing-screen").wireValue()).isEqualTo("always_compact");
    }

    private static String textOf(Row row) {
        StringBuilder out = new StringBuilder();
        for (var span : row.spans()) out.append(span.text());
        return out.toString();
    }
}
