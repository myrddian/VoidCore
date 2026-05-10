package io.aeyer.voidcore.ws.flow.screen.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.voidcore.acl.AclService;
import io.aeyer.voidcore.auth.UserRepository;
import io.aeyer.voidcore.instance.InstanceFeature;
import io.aeyer.voidcore.instance.InstanceFeatureProperties;
import io.aeyer.voidcore.instance.InstanceFeatureService;
import io.aeyer.voidcore.instance.ScreenSkinRegistry;
import io.aeyer.voidcore.instance.ThemeRegistry;
import io.aeyer.voidcore.ws.VoidCoreSession;
import io.aeyer.voidcore.ws.flow.ScreenRouter;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.BbsServices;
import io.aeyer.voidcore.ws.flow.screen.CustomScreenProvider;
import io.aeyer.voidcore.ws.flow.screen.CustomScreenRegistry;
import io.aeyer.voidcore.ws.flow.screen.Navigator;
import io.aeyer.voidcore.ws.protocol.ServerMessage;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MenuScreenTest {

    @TempDir
    Path tempDir;

    @Test
    void onEnterShowsWs360EntryAndOnKeyPushesCustomScreen() throws IOException {
        Navigator navigator = mock(Navigator.class);
        VoidCoreSession session = mock(VoidCoreSession.class);
        when(session.userId()).thenReturn(7L);

        BbsServices services = mock(BbsServices.class);
        when(services.currentTheme(7L)).thenReturn("ws360");
        InstanceFeatureService features = mock(InstanceFeatureService.class);
        when(services.instanceFeatures()).thenReturn(features);
        for (InstanceFeature feature : InstanceFeature.values()) {
            when(features.enabled(feature)).thenReturn(true);
        }

        AclService acl = mock(AclService.class);
        when(acl.hasAnyManageAccess(session)).thenReturn(false);

        ThemeRegistry themes = new ThemeRegistry(new ObjectMapper(),
                new InstanceFeatureProperties(List.of(), tempDir.toString()));
        ScreenSkinRegistry skins = new ScreenSkinRegistry(new ObjectMapper(),
                new InstanceFeatureProperties(List.of(), tempDir.toString()));
        when(services.skins()).thenReturn(skins);
        CustomScreenRegistry customScreens = new CustomScreenRegistry(
                List.of(new CustomScreenProvider() {
                    @Override public String screenName() { return "ws360/demo"; }
                    @Override public io.aeyer.voidcore.ws.flow.screen.Screen createScreen() { return null; }
                }),
                new ObjectMapper(),
                new InstanceFeatureProperties(List.of(), tempDir.toString()));

        MenuScreen screen = new MenuScreen(
                mock(UserRepository.class),
                new ObjectMapper(),
                new org.springframework.beans.factory.ObjectProvider<>() {
                    @Override public io.aeyer.voidcore.chat.ChatRepository getObject(Object... args) { return null; }
                    @Override public io.aeyer.voidcore.chat.ChatRepository getIfAvailable() { return null; }
                    @Override public io.aeyer.voidcore.chat.ChatRepository getIfUnique() { return null; }
                    @Override public io.aeyer.voidcore.chat.ChatRepository getObject() { return null; }
                },
                acl,
                themes,
                customScreens);

        UserRepository.UserRow user = new UserRepository.UserRow(7L, "enzo", "pw", false, false);
        BbsContext ctx = new BbsContext(session, user, navigator, services, mock(ScreenRouter.class), "main-menu");
        List<ServerMessage> sent = new ArrayList<>();
        doAnswer(inv -> {
            sent.add(inv.getArgument(0));
            return null;
        }).when(session).send(any(ServerMessage.class));

        screen.onEnter(ctx);
        screen.onKey(ctx, "X");

        ServerMessage.RegionUpdate main = sent.stream()
                .filter(ServerMessage.RegionUpdate.class::isInstance)
                .map(ServerMessage.RegionUpdate.class::cast)
                .filter(update -> "main".equals(update.region()))
                .findFirst()
                .orElseThrow();
        StringBuilder rendered = new StringBuilder();
        for (ServerMessage.Row row : main.content()) {
            row.spans().forEach(span -> rendered.append(span.text()).append('\n'));
        }
        ServerMessage.InputPrompt prompt = sent.stream()
                .filter(ServerMessage.InputPrompt.class::isInstance)
                .map(ServerMessage.InputPrompt.class::cast)
                .findFirst()
                .orElseThrow();

        assertThat(rendered.toString()).contains("[X] WS/360 demo");
        assertThat(prompt.valid_keys()).contains("X");
        verify(navigator).push(session, "ws360/demo");
    }
}
