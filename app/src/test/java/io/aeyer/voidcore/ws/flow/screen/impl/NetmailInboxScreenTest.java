package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.acl.AclPermission;
import io.aeyer.voidcore.acl.AclResourceType;
import io.aeyer.voidcore.acl.AclService;
import io.aeyer.voidcore.instance.InstanceFeature;
import io.aeyer.voidcore.instance.InstanceFeatureService;
import io.aeyer.voidcore.netmail.NetmailDraft;
import io.aeyer.voidcore.netmail.NetmailMessage;
import io.aeyer.voidcore.netmail.NetmailRepository;
import io.aeyer.voidcore.ws.VoidCoreSession;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.BbsServices;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.protocol.ServerMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NetmailInboxScreenTest {

    NetmailRepository netmail;
    AclService acl;
    BbsContext ctx;
    BbsServices services;
    VoidCoreSession session;
    NetmailInboxScreen screen;
    List<ServerMessage> sent;

    @BeforeEach
    void setUp() {
        netmail = mock(NetmailRepository.class);
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
        when(features.enabled(InstanceFeature.VOIDMAIL)).thenReturn(true);
        when(session.userId()).thenReturn(7L);
        when(netmail.inbox(7L)).thenReturn(List.of(
                new NetmailMessage(11L, 3L, 7L, "alice", "bob", "hello", "body",
                        OffsetDateTime.parse("2026-05-04T08:00:00Z"), null)));
        when(acl.can(session, AclResourceType.VOIDMAIL_SYSTEM, NetmailInboxScreen.SYSTEM_ID, AclPermission.VIEW))
                .thenReturn(true);
        when(acl.can(session, AclResourceType.VOIDMAIL_SYSTEM, NetmailInboxScreen.SYSTEM_ID, AclPermission.POST))
                .thenReturn(true);
        when(session.netmailDraft()).thenReturn(new NetmailDraft("alice", "subj", null));

        screen = new NetmailInboxScreen(netmail, acl);
    }

    @Test
    void onEnter_persists_inbox_and_emits_prompt() {
        screen.onEnter(ctx);

        verify(ctx).persistCurrentScreen("{\"kind\":\"netmail_inbox\"}");
        verify(ctx).send(any(ServerMessage.RegionUpdate.class));
        verify(ctx).send(any(ServerMessage.InputPrompt.class));
    }

    @Test
    void outbox_key_replaces_top_with_outbox() {
        screen.onKey(ctx, "O");

        verify(ctx).replaceTopAndEnter(Phase.NETMAIL_OUTBOX);
    }

    @Test
    void numeric_selection_pushes_read_screen() {
        screen.onKey(ctx, "1");

        verify(session).setCurrentNetmailId(11L);
        verify(ctx).push(Phase.NETMAIL_READ);
    }
}
