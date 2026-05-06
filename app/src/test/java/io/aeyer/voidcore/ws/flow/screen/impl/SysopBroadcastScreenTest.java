package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.BbsServices;
import io.aeyer.voidcore.ws.flow.screen.MentionService;
import io.aeyer.voidcore.ws.flow.ui.AppEvent;
import io.aeyer.voidcore.ws.protocol.ServerMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SysopBroadcastScreenTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    BbsContext ctx;
    BbsServices services;
    MentionService mentions;
    SysopBroadcastScreen screen;
    List<ServerMessage> sent;

    @BeforeEach
    void setUp() {
        ctx      = mock(BbsContext.class);
        services = mock(BbsServices.class);
        mentions = mock(MentionService.class);
        sent     = new ArrayList<>();
        doAnswer(inv -> { sent.add(inv.getArgument(0)); return null; })
            .when(ctx).send(any(ServerMessage.class));

        when(ctx.services()).thenReturn(services);
        when(services.mentions()).thenReturn(mentions);
        when(services.json()).thenReturn(JSON);
        when(mentions.broadcastAll(anyString(), anyInt())).thenReturn(5);

        screen = new SysopBroadcastScreen();
    }

    @Test
    void field_commit_with_body_broadcasts_and_pops() {
        screen.onEnter(ctx);
        screen.onAppEvent(ctx, new AppEvent.FieldCommit("text", "all hands"));
        verify(mentions).broadcastAll(eq("SYSOP: all hands"), eq(6000));
        verify(ctx).pop();
    }

    @Test
    void empty_field_commit_pops_without_broadcast() {
        screen.onEnter(ctx);
        screen.onAppEvent(ctx, new AppEvent.FieldCommit("text", ""));
        verify(mentions, never()).broadcastAll(anyString(), anyInt());
        verify(ctx).pop();
    }

    @Test
    void cancel_pops_screen() {
        screen.onEnter(ctx);
        screen.onCancel(ctx);
        verify(ctx).pop();
    }
}
