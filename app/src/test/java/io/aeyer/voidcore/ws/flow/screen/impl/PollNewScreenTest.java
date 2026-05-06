package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.acl.AclPermission;
import io.aeyer.voidcore.acl.AclResourceType;
import io.aeyer.voidcore.acl.AclService;
import io.aeyer.voidcore.instance.InstanceFeature;
import io.aeyer.voidcore.instance.InstanceFeatureService;
import io.aeyer.voidcore.polls.PollRepository;
import io.aeyer.voidcore.ws.VoidCoreSession;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.BbsServices;
import io.aeyer.voidcore.ws.flow.ui.AppEvent;
import io.aeyer.voidcore.ws.protocol.ServerMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PollNewScreenTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    PollRepository      polls;
    AclService          acl;
    BbsContext          ctx;
    BbsServices         services;
    VoidCoreSession        session;
    PollNewScreen       screen;
    List<ServerMessage> sent;

    @BeforeEach
    void setUp() {
        polls    = mock(PollRepository.class);
        acl      = mock(AclService.class);
        ctx      = mock(BbsContext.class);
        services = mock(BbsServices.class);
        session  = mock(VoidCoreSession.class);
        sent     = new ArrayList<>();

        doAnswer(inv -> { sent.add(inv.getArgument(0)); return null; })
            .when(ctx).send(any(ServerMessage.class));

        when(ctx.session()).thenReturn(session);
        when(ctx.services()).thenReturn(services);
        when(services.json()).thenReturn(JSON);
        InstanceFeatureService features = mock(InstanceFeatureService.class);
        when(services.instanceFeatures()).thenReturn(features);
        when(features.enabled(InstanceFeature.POLLS)).thenReturn(true);
        when(acl.can(session, AclResourceType.POLL, PollsListScreen.HUB_ID, AclPermission.POST)).thenReturn(true);
        // socialEvents returns null — achievement branch not exercised.
        when(services.socialEvents()).thenReturn(null);

        screen = new PollNewScreen(polls, acl);
    }

    // ─── Test 1: full happy path — 3 options ──────────────────────────

    @Test
    void wizard_creates_poll_with_three_options() {
        when(session.userId()).thenReturn(7L);
        when(polls.insert(anyLong(), anyString(), anyList())).thenReturn(99L);

        screen.onEnter(ctx);
        screen.onAppEvent(ctx, new AppEvent.FieldCommit("step", "Best year?"));
        screen.onAppEvent(ctx, new AppEvent.FieldCommit("step", "1995"));
        screen.onAppEvent(ctx, new AppEvent.FieldCommit("step", "1999"));
        screen.onAppEvent(ctx, new AppEvent.FieldCommit("step", "2024"));
        screen.onAppEvent(ctx, new AppEvent.FieldCommit("step", ""));  // blank ends loop

        verify(polls).insert(
            eq(7L),
            eq("Best year?"),
            argThat(list -> list.size() == 3));
        verify(ctx).pop();
    }

    // ─── Test 2: blank submit with only 1 option — stays on step 1 ────

    @Test
    void blank_submit_with_one_option_does_not_advance() {
        when(session.userId()).thenReturn(7L);

        screen.onEnter(ctx);
        screen.onAppEvent(ctx, new AppEvent.FieldCommit("step", "Best year?"));
        screen.onAppEvent(ctx, new AppEvent.FieldCommit("step", "1995"));
        screen.onAppEvent(ctx, new AppEvent.FieldCommit("step", ""));  // only 1 option → fail

        assertThat(screen.currentStepIndex()).isEqualTo(1);
        verify(polls, never()).insert(anyLong(), anyString(), anyList());
    }

    // ─── Test 3: tenth option is silently dropped, 9 options inserted ─

    @Test
    void tenth_option_is_silently_dropped() {
        when(session.userId()).thenReturn(7L);
        when(polls.insert(anyLong(), anyString(), anyList())).thenReturn(42L);

        screen.onEnter(ctx);
        screen.onAppEvent(ctx, new AppEvent.FieldCommit("step", "Favourite digit?"));
        // Submit 10 options
        for (int i = 1; i <= 10; i++) {
            screen.onAppEvent(ctx, new AppEvent.FieldCommit("step", "Option " + i));
        }
        screen.onAppEvent(ctx, new AppEvent.FieldCommit("step", ""));  // blank ends loop

        verify(polls).insert(
            eq(7L),
            eq("Favourite digit?"),
            argThat(list -> list.size() == 9));
        verify(ctx).pop();
    }
}
