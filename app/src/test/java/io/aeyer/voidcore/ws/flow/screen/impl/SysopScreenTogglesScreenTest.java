package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.instance.InstanceFeature;
import io.aeyer.voidcore.instance.InstanceFeatureService;
import io.aeyer.voidcore.instance.InstanceFeatureState;
import io.aeyer.voidcore.ws.VoidCoreSession;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.BbsServices;
import io.aeyer.voidcore.ws.protocol.ServerMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SysopScreenTogglesScreenTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    BbsContext ctx;
    BbsServices services;
    VoidCoreSession session;
    InstanceFeatureService features;
    SysopScreenTogglesScreen screen;
    List<ServerMessage> sent;

    @BeforeEach
    void setUp() {
        ctx = mock(BbsContext.class);
        services = mock(BbsServices.class);
        session = mock(VoidCoreSession.class);
        features = mock(InstanceFeatureService.class);
        sent = new ArrayList<>();

        doAnswer(inv -> { sent.add(inv.getArgument(0)); return null; })
                .when(ctx).send(any(ServerMessage.class));

        when(ctx.isSysop()).thenReturn(true);
        when(ctx.services()).thenReturn(services);
        when(ctx.session()).thenReturn(session);
        when(services.instanceFeatures()).thenReturn(features);
        when(services.json()).thenReturn(JSON);
        when(features.list()).thenReturn(List.of(
                new InstanceFeatureState(InstanceFeature.CHAT, true),
                new InstanceFeatureState(InstanceFeature.VOIDMAIL, false)));

        screen = new SysopScreenTogglesScreen();
    }

    @Test
    void onEnterPersistsAndPrompts() {
        screen.onEnter(ctx);

        verify(ctx).persistCurrentScreen("{\"kind\":\"sysop_screen_toggles\"}");
        verify(ctx).send(any(ServerMessage.RegionUpdate.class));
        verify(ctx).send(any(ServerMessage.InputPrompt.class));
    }

    @Test
    void numericToggleFlipsFeatureAndPublishes() {
        screen.onKey(ctx, "2");

        verify(features).setEnabled(InstanceFeature.VOIDMAIL, true);
        verify(ctx).publish(InstanceFeatureService.TOPIC);
    }
}
