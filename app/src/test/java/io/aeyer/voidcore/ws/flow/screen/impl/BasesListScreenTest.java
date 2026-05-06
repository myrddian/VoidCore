package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.acl.AclPermission;
import io.aeyer.voidcore.acl.AclResourceType;
import io.aeyer.voidcore.acl.AclService;
import io.aeyer.voidcore.instance.InstanceFeature;
import io.aeyer.voidcore.instance.InstanceFeatureService;
import io.aeyer.voidcore.messages.MessageBase;
import io.aeyer.voidcore.messages.MessageBaseRepository;
import io.aeyer.voidcore.messages.MessageBaseRepository.BaseWithUnread;
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

class BasesListScreenTest {

    MessageBaseRepository repo;
    AclService acl;
    BbsContext ctx;
    BbsServices services;
    VoidCoreSession session;
    BasesListScreen screen;
    List<ServerMessage> sent;

    @BeforeEach
    void setUp() {
        repo = mock(MessageBaseRepository.class);
        acl = mock(AclService.class);
        ctx = mock(BbsContext.class);
        services = mock(BbsServices.class);
        session = mock(VoidCoreSession.class);
        sent = new ArrayList<>();

        doAnswer(inv -> { sent.add(inv.getArgument(0)); return null; })
                .when(ctx).send(any(ServerMessage.class));

        when(ctx.session()).thenReturn(session);
        when(ctx.services()).thenReturn(services);
        InstanceFeatureService features = mock(InstanceFeatureService.class);
        when(services.instanceFeatures()).thenReturn(features);
        when(features.enabled(InstanceFeature.MESSAGE_BOARD)).thenReturn(true);
        when(session.userId()).thenReturn(7L);
        when(repo.listAllWithUnread(7L)).thenReturn(List.of(
                new BaseWithUnread(new MessageBase(1L, "general", "General", "", 1, false), 2),
                new BaseWithUnread(new MessageBase(2L, "staff", "Staff", "", 2, false), 1)));
        when(acl.can(session, AclResourceType.MESSAGE_BASE, 1L, AclPermission.VIEW)).thenReturn(true);
        when(acl.can(session, AclResourceType.MESSAGE_BASE, 2L, AclPermission.VIEW)).thenReturn(false);

        screen = new BasesListScreen(repo, acl);
    }

    @Test
    void onEnterFiltersBasesByViewAcl() {
        screen.onEnter(ctx);

        verify(ctx).persistCurrentScreen("{\"kind\":\"bases\"}");
        verify(ctx).send(any(ServerMessage.RegionUpdate.class));
        verify(ctx).send(any(ServerMessage.InputPrompt.class));
    }

    @Test
    void numericSelectionUsesFilteredOrdering() {
        screen.onEnter(ctx);
        screen.onKey(ctx, "1");

        verify(session).setSelectedBaseId(1L);
        verify(ctx).push(Phase.THREADS_LIST);
    }
}
