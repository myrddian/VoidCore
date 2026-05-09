package io.aeyer.voidcore.ws.flow.screen;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.voidcore.auth.UserRepository;
import io.aeyer.voidcore.instance.InstanceFeatureProperties;
import io.aeyer.voidcore.instance.ScreenSkinRegistry;
import io.aeyer.voidcore.ws.VoidCoreSession;
import io.aeyer.voidcore.ws.flow.Frames;
import io.aeyer.voidcore.ws.flow.layout.Element;
import io.aeyer.voidcore.ws.protocol.ServerMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BbsContextSkinningTest {

    @TempDir
    Path tempDir;

    @Test
    void sendAppliesBannerAndBodySkinsForMatchingScreen() throws Exception {
        Path skinDir = tempDir.resolve("skins").resolve("main-menu");
        Files.createDirectories(skinDir);
        Files.writeString(skinDir.resolve("voidcore-skin.json"), """
                {
                  "screenName": "main-menu",
                  "bannerPolicy": "always_compact",
                  "banner": { "asset": "banner.ans" },
                  "main": {
                    "asset": "main.ans",
                    "slots": [
                      { "name": "body", "row": 2, "col": 3, "width": 16, "height": 2 }
                    ]
                  }
                }
                """);
        Files.writeString(skinDir.resolve("banner.ans"), "WS/360 BANNER\n");
        Files.writeString(skinDir.resolve("main.ans"), """
                +--------------------+
                |                    |
                |                    |
                +--------------------+
                """);

        ScreenSkinRegistry skins = new ScreenSkinRegistry(
                new ObjectMapper(),
                new InstanceFeatureProperties(List.of(), tempDir.toString()));
        BbsServices services = mock(BbsServices.class);
        when(services.skins()).thenReturn(skins);

        VoidCoreSession session = mock(VoidCoreSession.class);
        Navigator navigator = mock(Navigator.class);
        BbsContext ctx = new BbsContext(
                session,
                new UserRepository.UserRow(7L, "enzo", "pw", false, false),
                navigator,
                services,
                null,
                "main-menu");
        java.util.List<ServerMessage> sent = new java.util.ArrayList<>();
        doAnswer(inv -> {
            sent.add(inv.getArgument(0));
            return null;
        }).when(session).send(any(ServerMessage.class));

        ctx.send(Frames.update("banner", 1, Frames.textRows(List.of("fallback"), "default")));
        ctx.send(Frames.update("main", 2, Frames.textRows(List.of("HELLO", "WORLD"), "default")));

        ServerMessage.RegionUpdate banner = sent.stream()
                .filter(ServerMessage.RegionUpdate.class::isInstance)
                .map(ServerMessage.RegionUpdate.class::cast)
                .filter(update -> "banner".equals(update.region()))
                .findFirst()
                .orElseThrow();
        ServerMessage.RegionUpdate main = sent.stream()
                .filter(ServerMessage.RegionUpdate.class::isInstance)
                .map(ServerMessage.RegionUpdate.class::cast)
                .filter(update -> "main".equals(update.region()))
                .findFirst()
                .orElseThrow();

        assertThat(textOf(banner.content())).contains("WS/360 BANNER");
        assertThat(banner.bannerPolicy()).isEqualTo("always_compact");
        assertThat(textOf(main.content())).contains("HELLO");
        assertThat(textOf(main.content())).contains("WORLD");
        assertThat(textOf(main.content())).contains("+--------------------+");
    }

    @Test
    void sendWrapsTreePayloadWhenTreeSkinMatchesScreen() throws Exception {
        Path skinDir = tempDir.resolve("skins").resolve("tree");
        Files.createDirectories(skinDir);
        Files.writeString(skinDir.resolve("voidcore-skin.json"), """
                {
                  "screenName": "ws360/demo",
                  "tree": {
                    "variant": "frame",
                    "headerTitle": "WS/360 TREE SHELL",
                    "headerRightAnnotation": "ACTIVE",
                    "paddingLeft": 1,
                    "topAssets": ["top.ans", "top2.ans"],
                    "leftAssets": ["left.ans", "left2.ans"],
                    "rightAssets": ["right.ans", "right2.ans"],
                    "bottomAssets": ["bottom.ans", "bottom2.ans"],
                    "footerText": "footer line",
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

        ScreenSkinRegistry skins = new ScreenSkinRegistry(
                new ObjectMapper(),
                new InstanceFeatureProperties(List.of(), tempDir.toString()));
        BbsServices services = mock(BbsServices.class);
        when(services.skins()).thenReturn(skins);

        VoidCoreSession session = mock(VoidCoreSession.class);
        Navigator navigator = mock(Navigator.class);
        BbsContext ctx = new BbsContext(
                session,
                new UserRepository.UserRow(7L, "enzo", "pw", false, false),
                navigator,
                services,
                null,
                "ws360/demo");
        java.util.List<ServerMessage> sent = new java.util.ArrayList<>();
        doAnswer(inv -> {
            sent.add(inv.getArgument(0));
            return null;
        }).when(session).send(any(ServerMessage.class));

        ctx.send(Frames.tree("main", 4, new Element.Text("body", "default"), null));

        ServerMessage.RegionUpdate main = sent.stream()
                .filter(ServerMessage.RegionUpdate.class::isInstance)
                .map(ServerMessage.RegionUpdate.class::cast)
                .filter(update -> "main".equals(update.region()))
                .findFirst()
                .orElseThrow();

        assertThat(main.tree()).isInstanceOf(Element.Shell.class);
        Element.Shell shell = (Element.Shell) main.tree();
        assertThat(shell.top()).isInstanceOf(Element.VStack.class);
        assertThat(shell.left()).isInstanceOf(Element.VStack.class);
        assertThat(shell.body()).isInstanceOf(Element.Padded.class);
        assertThat(shell.right()).isInstanceOf(Element.VStack.class);
        assertThat(shell.bottom()).isInstanceOf(Element.VStack.class);
    }

    private static String textOf(List<ServerMessage.Row> rows) {
        StringBuilder out = new StringBuilder();
        for (ServerMessage.Row row : rows) {
            row.spans().forEach(span -> out.append(span.text()));
            out.append('\n');
        }
        return out.toString();
    }
}
