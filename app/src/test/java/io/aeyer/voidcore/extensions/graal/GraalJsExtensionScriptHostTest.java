package io.aeyer.voidcore.extensions.graal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.voidcore.auth.UserRepository;
import io.aeyer.voidcore.extensions.ExtensionDataRepository;
import io.aeyer.voidcore.extensions.ExtensionDataService;
import io.aeyer.voidcore.extensions.ExtensionScreenRegistration;
import io.aeyer.voidcore.extensions.host.HostedExtensionScreenRuntime;
import io.aeyer.voidcore.ws.VoidCoreSession;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.BbsServices;
import io.aeyer.voidcore.ws.flow.screen.Navigator;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.flow.screen.Screen;
import io.aeyer.voidcore.ws.flow.screen.ScreenRoute;
import io.aeyer.voidcore.ws.flow.view.DocumentView;
import io.aeyer.voidcore.ws.protocol.ServerMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GraalJsExtensionScriptHostTest {

    @Test
    void graalHostLoadsScriptAndRoutesAllAccessThroughProxies(@TempDir Path tempDir) throws Exception {
        Path extensionRoot = tempDir.resolve("aeyer");
        Files.createDirectories(extensionRoot);
        Files.writeString(extensionRoot.resolve("releases.js"), """
                voidcore.registerScreen({
                  onEnter(ctx) {
                    const doc = ctx.docBySlug("release-42");
                    ctx.banner("AEYER Releases");
                    ctx.mainText([
                      "Loaded: " + doc.title,
                      "Route: " + ctx.session.currentRoute
                    ]);
                    ctx.putForCurrentUser("last-viewed", {
                      slug: doc.slug,
                      tags: doc.tags,
                      route: ctx.session.currentRoute
                    });
                    ctx.promptKeystroke("cmd:", "NMQ");
                  },
                  onKey(ctx, key) {
                    if (key === "N") ctx.push("aeyer/next");
                    if (key === "M") ctx.pushCore("MENU");
                    if (key === "Q") ctx.pop();
                  }
                });
                """);

        ObjectMapper json = new ObjectMapper();
        ExtensionDataRepository repo = mock(ExtensionDataRepository.class);
        ExtensionDataService data = new ExtensionDataService(repo);
        GraalJsExtensionScriptHost host = new GraalJsExtensionScriptHost(json);
        HostedExtensionScreenRuntime runtime = new HostedExtensionScreenRuntime(List.of(host), data);

        DocumentView docs = mock(DocumentView.class);
        io.aeyer.voidcore.documents.DocumentRow release = new io.aeyer.voidcore.documents.DocumentRow(
                42L,
                "release-42",
                "Release 42",
                "release",
                1,
                1,
                "Body",
                json.createObjectNode().put("catalog", "AEYER-42"),
                List.of("electro", "ambient"),
                7L,
                io.aeyer.voidcore.documents.Visibility.PUBLIC,
                io.aeyer.voidcore.documents.Status.PUBLISHED,
                OffsetDateTime.now(),
                OffsetDateTime.now(),
                null,
                null,
                null);
        when(docs.bySlug("release-42")).thenReturn(Optional.of(release));
        when(docs.canRead(any(), eq(release))).thenReturn(true);

        BbsServices services = mock(BbsServices.class);
        when(services.documents()).thenReturn(docs);

        Navigator navigator = mock(Navigator.class);
        VoidCoreSession session = mock(VoidCoreSession.class);
        when(session.id()).thenReturn("graal-screen");
        when(navigator.currentRoute(session)).thenReturn(ScreenRoute.custom("aeyer/releases"));

        List<ServerMessage> sent = new ArrayList<>();
        doAnswer(inv -> {
            sent.add(inv.getArgument(0));
            return null;
        }).when(session).send(any());

        UserRepository.UserRow user = new UserRepository.UserRow(7L, "enzo", "pw", true, false);
        BbsContext ctx = new BbsContext(session, user, navigator, services, new Object());
        ExtensionScreenRegistration registration = new ExtensionScreenRegistration(
                "aeyer",
                "AEYER Overlay",
                "0.1.0",
                "aeyer/releases",
                "AEYER Releases",
                extensionRoot,
                extensionRoot.resolve("voidcore-extension.json"),
                "releases.js",
                extensionRoot.resolve("releases.js"),
                List.of("documents.read", "extensions_data.user"),
                List.of("release"));

        Screen screen = runtime.createScreen(registration);
        screen.onEnter(ctx);
        screen.onKey(ctx, "N");
        screen.onKey(ctx, "M");
        screen.onKey(ctx, "Q");

        verify(docs).bySlug("release-42");
        verify(navigator).push(session, "aeyer/next");
        verify(navigator).push(session, Phase.MENU);
        verify(navigator).pop(session);
        org.mockito.ArgumentCaptor<JsonNode> payloadCaptor =
                org.mockito.ArgumentCaptor.forClass(JsonNode.class);
        verify(repo).put(eq("aeyer"), eq("user"), eq("7"), eq("last-viewed"), payloadCaptor.capture());
        JsonNode payload = payloadCaptor.getValue();
        assertThat(payload.path("slug").asText()).isEqualTo("release-42");
        assertThat(payload.path("route").asText()).isEqualTo("aeyer/releases");
        assertThat(payload.path("tags").size()).isEqualTo(2);
        assertThat(sent).anySatisfy(message -> assertThat(message).isInstanceOf(ServerMessage.RegionUpdate.class));
        assertThat(sent).anySatisfy(message -> assertThat(message).isInstanceOf(ServerMessage.InputPrompt.class));
        verify(session, atLeastOnce()).send(any());
    }

    @Test
    void graalHostCanBuildTreeUiFromJsDsl(@TempDir Path tempDir) throws Exception {
        Path extensionRoot = tempDir.resolve("aeyer");
        Files.createDirectories(extensionRoot);
        Files.writeString(extensionRoot.resolve("tree.js"), """
                voidcore.registerScreen({
                  onEnter(ctx) {
                    ctx.render(
                      ctx.el.vstack([
                        ctx.el.header("AEYER", "JS"),
                        ctx.el.text("Hello from tree mode", "bright_cyan"),
                        ctx.el.keyMenu([
                          ctx.el.keyEntry("Q", "Back")
                        ])
                      ], 1),
                      null
                    );
                    ctx.promptKeystroke("cmd:", "Q");
                  }
                });
                """);

        ObjectMapper json = new ObjectMapper();
        HostedExtensionScreenRuntime runtime = new HostedExtensionScreenRuntime(
                List.of(new GraalJsExtensionScriptHost(json)),
                new ExtensionDataService(mock(ExtensionDataRepository.class)));

        BbsServices services = mock(BbsServices.class);
        Navigator navigator = mock(Navigator.class);
        VoidCoreSession session = mock(VoidCoreSession.class);
        when(session.id()).thenReturn("graal-tree");
        List<ServerMessage> sent = new ArrayList<>();
        doAnswer(inv -> {
            sent.add(inv.getArgument(0));
            return null;
        }).when(session).send(any());

        BbsContext ctx = new BbsContext(session, null, navigator, services, new Object());
        Screen screen = runtime.createScreen(new ExtensionScreenRegistration(
                "aeyer",
                "AEYER Overlay",
                "0.1.0",
                "aeyer/tree",
                "AEYER Tree",
                extensionRoot,
                extensionRoot.resolve("voidcore-extension.json"),
                "tree.js",
                extensionRoot.resolve("tree.js"),
                List.of(),
                List.of()));

        screen.onEnter(ctx);

        ServerMessage.RegionUpdate main = sent.stream()
                .filter(ServerMessage.RegionUpdate.class::isInstance)
                .map(ServerMessage.RegionUpdate.class::cast)
                .filter(update -> "main".equals(update.region()))
                .findFirst()
                .orElseThrow();
        assertThat(main.tree()).isNotNull();
        assertThat(main.tree()).isInstanceOf(io.aeyer.voidcore.ws.flow.layout.Element.VStack.class);
    }
}
