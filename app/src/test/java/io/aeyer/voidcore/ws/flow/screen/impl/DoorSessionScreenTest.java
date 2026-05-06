package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.doors.DoorPromptState;
import io.aeyer.voidcore.doors.DoorRuntimeService;
import io.aeyer.voidcore.doors.DoorSessionState;
import io.aeyer.voidcore.instance.InstanceFeature;
import io.aeyer.voidcore.instance.InstanceFeatureService;
import io.aeyer.voidcore.ws.VoidCoreSession;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.BbsServices;
import io.aeyer.voidcore.ws.protocol.ServerMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DoorSessionScreenTest {

    DoorRuntimeService doors;
    BbsContext ctx;
    BbsServices services;
    VoidCoreSession session;
    DoorSessionScreen screen;
    List<ServerMessage> sent;

    @BeforeEach
    void setUp() {
        doors = mock(DoorRuntimeService.class);
        ctx = mock(BbsContext.class);
        services = mock(BbsServices.class);
        session = mock(VoidCoreSession.class);
        sent = new ArrayList<>();

        doAnswer(inv -> { sent.add(inv.getArgument(0)); return null; })
                .when(ctx).send(any(ServerMessage.class));

        when(ctx.services()).thenReturn(services);
        when(ctx.session()).thenReturn(session);
        when(ctx.currentTheme()).thenReturn("phosphor");
        when(session.id()).thenReturn("sess-1");
        when(session.currentDoorId()).thenReturn("cityline-mud");
        when(doors.topicFor("sess-1")).thenReturn("door-session:sess-1");
        when(doors.stateFor(session)).thenReturn(new DoorSessionState(
                "cityline-mud", "Cityline MUD", "door-sess-1",
                DoorSessionState.Status.ACTIVE,
                List.of(new ServerMessage.Row(0, List.of(new ServerMessage.Span("hello", "default", null, null)))),
                new DoorPromptState("line", "cmd:", 80, null),
                null,
                3));

        InstanceFeatureService features = mock(InstanceFeatureService.class);
        when(services.instanceFeatures()).thenReturn(features);
        when(features.enabled(InstanceFeature.DOORS)).thenReturn(true);

        screen = new DoorSessionScreen(doors);
    }

    @Test
    void onEnterEnsuresAttachmentAndPersistsDoorScreen() {
        screen.onEnter(ctx);

        verify(doors).ensureAttached(session, "cityline-mud", "phosphor");
        verify(ctx).persistCurrentScreen("{\"kind\":\"door_session\",\"door_id\":\"cityline-mud\"}");
        verify(ctx, atLeastOnce()).send(any(ServerMessage.RegionUpdate.class));
        verify(ctx).send(any(ServerMessage.InputPrompt.class));
    }

    @Test
    void cancelForwardsEscapeIntoDoorSession() {
        screen.onCancel(ctx);

        verify(doors).sendInputKey(session, "ESCAPE");
    }
}
