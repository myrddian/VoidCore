package io.aeyer.voidcore.extensions.host;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aeyer.voidcore.auth.UserRepository;
import io.aeyer.voidcore.extensions.ExtensionDataRepository;
import io.aeyer.voidcore.extensions.ExtensionDataService;
import io.aeyer.voidcore.extensions.ExtensionScreenRegistration;
import io.aeyer.voidcore.ws.VoidCoreSession;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.BbsServices;
import io.aeyer.voidcore.ws.flow.screen.Navigator;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.flow.screen.Screen;
import io.aeyer.voidcore.ws.flow.view.DocumentView;
import io.aeyer.voidcore.ws.protocol.ServerMessage;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HostedExtensionScreenRuntimeTest {

    @Test
    void hostBackedScreenDelegatesLifecycleThroughCuratedContext() throws Exception {
        ExtensionDataRepository repo = mock(ExtensionDataRepository.class);
        ExtensionDataService data = new ExtensionDataService(repo);
        ObjectMapper json = new ObjectMapper();
        ObjectNode payload = json.createObjectNode().put("seen", true);
        DocumentView docs = mock(DocumentView.class);
        io.aeyer.voidcore.documents.DocumentRow release = new io.aeyer.voidcore.documents.DocumentRow(
                42L, "release-42", "Release 42", "release", 1, 1, "body",
                json.createObjectNode().put("catalog", "AEYER-42"),
                List.of("electro"), 7L,
                io.aeyer.voidcore.documents.Visibility.PUBLIC,
                io.aeyer.voidcore.documents.Status.PUBLISHED,
                java.time.OffsetDateTime.now(),
                java.time.OffsetDateTime.now(),
                null,
                null,
                null);
        when(docs.bySlug("release-42")).thenReturn(Optional.of(release));
        when(docs.canRead(any(), any())).thenReturn(true);

        BbsServices services = mock(BbsServices.class);
        when(services.documents()).thenReturn(docs);

        Navigator navigator = mock(Navigator.class);
        VoidCoreSession session = mock(VoidCoreSession.class);
        when(session.id()).thenReturn("ext-screen");
        doAnswer(inv -> null).when(session).send(any());

        UserRepository.UserRow user = new UserRepository.UserRow(7L, "enzo", "pw", true, false);
        BbsContext ctx = new BbsContext(session, user, navigator, services, new Object());
        ExtensionScreenRegistration registration = new ExtensionScreenRegistration(
                "aeyer",
                "AEYER Overlay",
                "0.1.0",
                "aeyer/releases",
                "AEYER Releases",
                Path.of("/instance/extensions/aeyer"),
                Path.of("/instance/extensions/aeyer/voidcore-extension.json"),
                "releases.js",
                Path.of("/instance/extensions/aeyer/releases.js"),
                List.of("documents.read", "extensions_data.user"),
                List.of("release"));

        ExtensionScriptHost host = reg -> Optional.of(new ExtensionScript() {
            @Override
            public void onEnter(ExtensionHostContext hostCtx) {
                hostCtx.ui().banner("AEYER Releases");
                String title = hostCtx.documents().bySlug("release-42")
                        .map(ExtensionDocumentView::title)
                        .orElse("<missing>");
                hostCtx.ui().mainText(List.of("Loaded: " + title));
                hostCtx.ui().promptKeystroke("cmd:", "Q");
                hostCtx.data().putForCurrentUser("last-viewed", payload);
            }

            @Override
            public void onKey(ExtensionHostContext hostCtx, String key) {
                if ("N".equals(key)) {
                    hostCtx.navigation().pushCustom("aeyer/next");
                } else if ("M".equals(key)) {
                    hostCtx.navigation().pushCore(Phase.MENU);
                }
            }

            @Override
            public void onCancel(ExtensionHostContext hostCtx) {
                hostCtx.navigation().pop();
            }
        });

        HostedExtensionScreenRuntime runtime =
                new HostedExtensionScreenRuntime(List.of(host), data);

        Screen screen = runtime.createScreen(registration);
        screen.onEnter(ctx);
        screen.onKey(ctx, "N");
        screen.onKey(ctx, "M");
        screen.onCancel(ctx);

        verify(repo).put("aeyer", "user", "7", "last-viewed", payload);
        verify(navigator).push(session, "aeyer/next");
        verify(navigator).push(session, Phase.MENU);
        verify(navigator).pop(session);
        verify(docs).bySlug("release-42");
        verify(session, atLeastOnce()).send(any(ServerMessage.RegionUpdate.class));
        verify(session, atLeastOnce()).send(any(ServerMessage.InputPrompt.class));
        assertThat(screen.name()).isEqualTo("custom-screen:aeyer/releases");
    }

    @Test
    void fallsBackToPlaceholderWhenNoHostClaimsRegistration() {
        HostedExtensionScreenRuntime runtime =
                new HostedExtensionScreenRuntime(List.of(), new ExtensionDataService(mock(ExtensionDataRepository.class)));

        Screen screen = runtime.createScreen(new ExtensionScreenRegistration(
                "aeyer",
                "AEYER Overlay",
                "0.1.0",
                "aeyer/releases",
                "AEYER Releases",
                Path.of("/instance/extensions/aeyer"),
                Path.of("/instance/extensions/aeyer/voidcore-extension.json"),
                "releases.js",
                Path.of("/instance/extensions/aeyer/releases.js"),
                List.of(),
                List.of()));

        assertThat(screen.name()).isEqualTo("custom-screen:aeyer/releases");
        assertThat(screen.phase()).isEqualTo(Phase.MENU);
    }
}
