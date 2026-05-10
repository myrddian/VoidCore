package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.branding.BrandingProperties;
import io.aeyer.voidcore.instance.InstanceFeatureProperties;
import io.aeyer.voidcore.instance.ScreenSkinRegistry;
import io.aeyer.voidcore.presence.PresenceService;
import io.aeyer.voidcore.ws.VoidCoreSession;
import io.aeyer.voidcore.ws.flow.Banner;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.BbsServices;
import io.aeyer.voidcore.ws.flow.screen.Navigator;
import io.aeyer.voidcore.ws.protocol.ServerMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LoginHandleScreenTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        Banner.configure(new BrandingProperties(null, null, null, null, null, null, null, null));
    }

    @Test
    void onEnterUsesConfiguredBrandingForConnectSequence() throws IOException {
        PresenceService presence = mock(PresenceService.class);
        when(presence.activeCount()).thenReturn(4);
        BrandingProperties branding = new BrandingProperties("Nebula Station", "quiet signal", "deep-space relay", "night relay",
                "MORPH", "21:2/9", "555-1212", "9600");
        Banner.configure(branding);
        LoginHandleScreen screen = new LoginHandleScreen(branding, presence);
        VoidCoreSession session = mock(VoidCoreSession.class);
        Navigator navigator = mock(Navigator.class);
        BbsServices services = mock(BbsServices.class);
        ScreenSkinRegistry skins = new ScreenSkinRegistry(
                new ObjectMapper(),
                new InstanceFeatureProperties(List.of(), tempDir.toString()));
        when(services.skins()).thenReturn(skins);
        BbsContext ctx = new BbsContext(session, null, navigator, services, null, "login-handle");
        List<ServerMessage> sent = new ArrayList<>();
        doAnswer(inv -> {
            sent.add(inv.getArgument(0));
            return null;
        }).when(session).send(any(ServerMessage.class));

        screen.onEnter(ctx);

        ServerMessage.RegionUpdate banner = sent.stream()
                .filter(msg -> msg instanceof ServerMessage.RegionUpdate ru && "banner".equals(ru.region()))
                .map(msg -> (ServerMessage.RegionUpdate) msg)
                .findFirst()
                .orElseThrow();
        ServerMessage.RegionUpdate main = sent.stream()
                .filter(msg -> msg instanceof ServerMessage.RegionUpdate ru && "main".equals(ru.region()))
                .map(msg -> (ServerMessage.RegionUpdate) msg)
                .findFirst()
                .orElseThrow();

        assertThat(banner.content().get(1).spans().getFirst().text()).contains("N E B U L A");
        assertThat(main.content().get(0).spans().getFirst().text()).isEqualTo("ATDT 555-1212");
        assertThat(main.content().get(1).spans().getFirst().text()).isEqualTo("CONNECT 9600");
        assertThat(main.content().get(4).spans().getFirst().text()).contains("NODE 05");
        assertThat(main.content().get(6).spans().getFirst().text()).contains("FidoNet 21:2/9");
    }
}
