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
import io.aeyer.voidcore.ws.protocol.ServerMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NetmailReadScreenTest {

    NetmailRepository netmail;
    AclService acl;
    BbsContext ctx;
    BbsServices services;
    VoidCoreSession session;
    NetmailReadScreen screen;
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
        when(session.currentNetmailId()).thenReturn(22L);
        when(acl.can(session, AclResourceType.VOIDMAIL_SYSTEM, NetmailInboxScreen.SYSTEM_ID, AclPermission.VIEW))
                .thenReturn(true);
        when(acl.can(session, AclResourceType.VOIDMAIL_SYSTEM, NetmailInboxScreen.SYSTEM_ID, AclPermission.POST))
                .thenReturn(true);

        screen = new NetmailReadScreen(netmail, acl);
    }

    @Test
    void reply_prefills_draft_for_incoming_message() {
        NetmailMessage incoming = new NetmailMessage(
                22L, 3L, 7L, "alice", "bob", "hello", "line one\nline two",
                OffsetDateTime.parse("2026-05-04T10:00:00Z"), null);
        when(netmail.findOwned(22L, 7L)).thenReturn(Optional.of(incoming));

        screen.onKey(ctx, "R");

        verify(session).setNetmailDraft(new NetmailDraft(
                "alice",
                "Re: hello",
                "\n\n---\nFrom: alice\nSubject: hello\n\n> line one\n> line two"));
        verify(ctx).replaceTopAndEnter(io.aeyer.voidcore.ws.flow.screen.Phase.NETMAIL_COMPOSE);
    }

    @Test
    void forward_prefills_draft_for_owned_message() {
        NetmailMessage outgoing = new NetmailMessage(
                22L, 7L, 9L, "bob", "charlie", "status", "body",
                OffsetDateTime.parse("2026-05-04T10:00:00Z"), null);
        when(netmail.findOwned(22L, 7L)).thenReturn(Optional.of(outgoing));

        screen.onKey(ctx, "F");

        verify(session).setNetmailDraft(new NetmailDraft(
                null,
                "Fwd: status",
                "\n\n---\nFrom: bob\nSubject: status\n\n> body"));
    }
}
