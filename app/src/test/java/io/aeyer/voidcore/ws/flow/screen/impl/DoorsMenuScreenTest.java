package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.doors.DoorRuntimeService;
import io.aeyer.voidcore.doors.DoorSummary;
import io.aeyer.voidcore.instance.InstanceFeature;
import io.aeyer.voidcore.instance.InstanceFeatureService;
import io.aeyer.voidcore.ws.VoidCoreSession;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.BbsServices;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.protocol.ServerMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DoorsMenuScreenTest {

    DoorRuntimeService doors;
    BbsContext ctx;
    BbsServices services;
    VoidCoreSession session;
    DoorsMenuScreen screen;
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
        InstanceFeatureService features = mock(InstanceFeatureService.class);
        when(services.instanceFeatures()).thenReturn(features);
        when(features.enabled(InstanceFeature.DOORS)).thenReturn(true);
        when(doors.listConnectedDoors()).thenReturn(List.of(
                new DoorSummary("cityline-mud", "Cityline MUD", "Cyberpunk node-space", 2)));

        screen = new DoorsMenuScreen(doors);
    }

    @Test
    void onEnterPersistsDoorsMenuAndClearsCurrentDoor() {
        screen.onEnter(ctx);

        verify(session).setCurrentDoorId(null);
        verify(ctx).persistCurrentScreen("{\"kind\":\"doors_menu\"}");
        verify(ctx).send(any(ServerMessage.RegionUpdate.class));
        verify(ctx).send(any(ServerMessage.InputPrompt.class));
    }

    @Test
    void numericSelectionStoresDoorIdAndPushesDoorSession() {
        screen.onKey(ctx, "1");

        verify(session).setCurrentDoorId("cityline-mud");
        verify(ctx).push(Phase.DOOR_SESSION);
    }
}
