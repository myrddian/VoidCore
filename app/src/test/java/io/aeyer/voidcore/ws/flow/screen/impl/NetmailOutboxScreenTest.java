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

class NetmailOutboxScreenTest {

    NetmailRepository netmail;
    AclService acl;
    BbsContext ctx;
    BbsServices services;
    VoidCoreSession session;
    NetmailOutboxScreen screen;
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
        when(session.netmailDraft()).thenReturn(new NetmailDraft(null, "fwd", "body"));
        when(netmail.outbox(7L)).thenReturn(List.of(
                new NetmailMessage(12L, 7L, 9L, "bob", "charlie", "sent", "body",
                        OffsetDateTime.parse("2026-05-04T09:00:00Z"), null)));
        when(acl.can(session, AclResourceType.VOIDMAIL_SYSTEM, NetmailInboxScreen.SYSTEM_ID, AclPermission.VIEW))
                .thenReturn(true);
        when(acl.can(session, AclResourceType.VOIDMAIL_SYSTEM, NetmailInboxScreen.SYSTEM_ID, AclPermission.POST))
                .thenReturn(true);

        screen = new NetmailOutboxScreen(netmail, acl);
    }

    @Test
    void onEnter_persists_outbox_and_emits_prompt() {
        screen.onEnter(ctx);

        verify(ctx).persistCurrentScreen("{\"kind\":\"netmail_outbox\"}");
        verify(ctx).send(any(ServerMessage.RegionUpdate.class));
        verify(ctx).send(any(ServerMessage.InputPrompt.class));
    }

    @Test
    void inbox_key_replaces_top_with_inbox() {
        screen.onKey(ctx, "I");

        verify(ctx).replaceTopAndEnter(Phase.NETMAIL_INBOX);
    }

    @Test
    void numeric_selection_pushes_read_screen() {
        screen.onKey(ctx, "1");

        verify(session).setCurrentNetmailId(12L);
        verify(ctx).push(Phase.NETMAIL_READ);
    }
}
