package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.acl.AclPermission;
import io.aeyer.voidcore.acl.AclResourceType;
import io.aeyer.voidcore.acl.AclService;
import io.aeyer.voidcore.auth.UserRepository;
import io.aeyer.voidcore.auth.UserRepository.UserRow;
import io.aeyer.voidcore.instance.InstanceFeature;
import io.aeyer.voidcore.instance.InstanceFeatureService;
import io.aeyer.voidcore.netmail.NetmailDraft;
import io.aeyer.voidcore.netmail.NetmailRepository;
import io.aeyer.voidcore.ws.VoidCoreSession;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.BbsServices;
import io.aeyer.voidcore.ws.flow.screen.MentionService;
import io.aeyer.voidcore.ws.flow.ui.AppEvent;
import io.aeyer.voidcore.ws.flow.layout.Element;
import io.aeyer.voidcore.ws.protocol.ServerMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NetmailComposeScreenTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    NetmailRepository      netmail;
    UserRepository         users;
    AclService             acl;
    BbsContext             ctx;
    BbsServices            services;
    VoidCoreSession           session;
    MentionService         mentions;
    NetmailComposeScreen   screen;
    List<ServerMessage>    sent;

    // A resolved recipient for the "happy-path" tests.
    static final UserRow ALICE = new UserRow(99L, "alice", "hash", false, false);
    // The sender.
    static final UserRow SENDER = new UserRow(42L, "bob", "hash", false, false);

    @BeforeEach
    void setUp() {
        netmail  = mock(NetmailRepository.class);
        users    = mock(UserRepository.class);
        acl      = mock(AclService.class);
        ctx      = mock(BbsContext.class);
        services = mock(BbsServices.class);
        session  = mock(VoidCoreSession.class);
        mentions = mock(MentionService.class);
        sent     = new ArrayList<>();

        doAnswer(inv -> { sent.add(inv.getArgument(0)); return null; })
            .when(ctx).send(any(ServerMessage.class));

        when(ctx.session()).thenReturn(session);
        when(ctx.services()).thenReturn(services);
        InstanceFeatureService features = mock(InstanceFeatureService.class);
        when(services.instanceFeatures()).thenReturn(features);
        when(features.enabled(InstanceFeature.VOIDMAIL)).thenReturn(true);
        when(session.netmailDraft()).thenReturn(null);
        when(acl.can(session, AclResourceType.VOIDMAIL_SYSTEM, NetmailInboxScreen.SYSTEM_ID, AclPermission.POST))
            .thenReturn(true);
        when(services.json()).thenReturn(JSON);
        when(services.mentions()).thenReturn(mentions);
        // socialEvents returns null — achievement path not exercised here.
        when(services.socialEvents()).thenReturn(null);

        when(session.userId()).thenReturn(42L);
        when(users.findByHandle("alice")).thenReturn(Optional.of(ALICE));
        when(users.findByHandle("nobody")).thenReturn(Optional.empty());
        when(users.findById(42L)).thenReturn(Optional.of(SENDER));
        when(netmail.insert(anyLong(), anyLong(), anyString(), anyString())).thenReturn(1L);

        screen = new NetmailComposeScreen(netmail, users, acl);
    }

    // ─── Test 1: happy path — full 3-step wizard sends the netmail ────────────

    @Test
    void wizard_sends_netmail_via_three_steps() {
        screen.onEnter(ctx);

        // Step 0: To
        screen.onAppEvent(ctx, new AppEvent.FieldCommit("step", "alice"));
        assertThat(screen.currentStepIndex()).isEqualTo(1);

        // Step 1: Subject
        screen.onAppEvent(ctx, new AppEvent.FieldCommit("step", "Hello!"));
        assertThat(screen.currentStepIndex()).isEqualTo(2);

        // Step 2: Body (multi-line editor commit)
        screen.onAppEvent(ctx, new AppEvent.EditorCommit("step", "How are you?", "save"));

        // Verify the netmail was inserted with the correct args.
        verify(netmail).insert(eq(42L), eq(99L), eq("Hello!"), eq("How are you?"));

        // Verify the recipient's inbox topic was published.
        verify(ctx).publish(NetmailInboxScreen.topicFor(99L));
        // And the sender's own mail views refresh too (outbox / compose return path).
        verify(ctx).publish(NetmailInboxScreen.topicFor(42L));

        // Verify a targeted notification was sent to the recipient.
        verify(mentions).notifyUser(eq(99L), any(), anyString(), anyInt());

        // Wizard should have popped after submit.
        verify(ctx).pop();
    }

    // ─── Test 2: unknown recipient keeps wizard at step 0 ─────────────────────

    @Test
    void unknown_recipient_rejects_step_0() {
        screen.onEnter(ctx);

        screen.onAppEvent(ctx, new AppEvent.FieldCommit("step", "nobody"));

        assertThat(screen.currentStepIndex()).isEqualTo(0);
        verify(netmail, never()).insert(anyLong(), anyLong(), anyString(), anyString());
    }

    // ─── Test 3: empty subject keeps wizard at step 1 ─────────────────────────
    // NOTE: Per BBS tradition the subject wizard step allows empty input
    // (it becomes "(no subject)"), so this test uses a whitespace-only
    // body to exercise step 2 rejection instead.  The actual "empty subject
    // is fine" behaviour is verified by the fact that step 1 always passes.
    // We test that the screen DOES advance past an empty subject line.

    @Test
    void empty_subject_advances_to_body_step() {
        screen.onEnter(ctx);

        // To — valid
        screen.onAppEvent(ctx, new AppEvent.FieldCommit("step", "alice"));
        assertThat(screen.currentStepIndex()).isEqualTo(1);

        // Subject — empty is allowed (BBS tradition: becomes "(no subject)")
        screen.onAppEvent(ctx, new AppEvent.FieldCommit("step", ""));
        assertThat(screen.currentStepIndex()).isEqualTo(2);

        verify(netmail, never()).insert(anyLong(), anyLong(), anyString(), anyString());
    }

    // ─── Test 4: empty body keeps wizard at step 2 ────────────────────────────

    @Test
    void empty_body_rejects_step_2() {
        screen.onEnter(ctx);

        // To
        screen.onAppEvent(ctx, new AppEvent.FieldCommit("step", "alice"));
        // Subject
        screen.onAppEvent(ctx, new AppEvent.FieldCommit("step", "Some subject"));
        assertThat(screen.currentStepIndex()).isEqualTo(2);

        // Body — blank
        screen.onAppEvent(ctx, new AppEvent.EditorCommit("step", "   ", "save"));

        assertThat(screen.currentStepIndex()).isEqualTo(2);
        verify(netmail, never()).insert(anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    void on_enter_uses_voidmail_copy_without_repeating_the_banner_text() {
        screen.onEnter(ctx);

        ServerMessage.RegionUpdate banner = sent.stream()
            .filter(ServerMessage.RegionUpdate.class::isInstance)
            .map(ServerMessage.RegionUpdate.class::cast)
            .filter(msg -> "banner".equals(msg.region()))
            .findFirst()
            .orElseThrow();
        String bannerText = banner.content().stream()
            .flatMap(row -> row.spans().stream())
            .map(ServerMessage.Span::text)
            .reduce("", String::concat);

        ServerMessage.RegionUpdate main = sent.stream()
            .filter(ServerMessage.RegionUpdate.class::isInstance)
            .map(ServerMessage.RegionUpdate.class::cast)
            .filter(msg -> "main".equals(msg.region()))
            .findFirst()
            .orElseThrow();
        Element.VStack tree = (Element.VStack) main.tree();
        Element.Header header = (Element.Header) tree.children().get(0);

        assertThat(bannerText).contains("VOIDMAIL-COMPOSE");
        assertThat(bannerText).doesNotContain("NETMAIL");
        assertThat(header.title()).isEqualTo("VOIDMAIL");
        assertThat(header.rightAnnotation()).isEqualTo("To");
    }

    @Test
    void escape_on_first_field_abandons_the_wizard() {
        screen.onEnter(ctx);

        screen.onAppEvent(ctx, new AppEvent.FieldCancel("step"));

        verify(ctx).pop();
    }

    @Test
    void escape_on_subject_step_returns_to_recipient_step() {
        screen.onEnter(ctx);
        screen.onAppEvent(ctx, new AppEvent.FieldCommit("step", "alice"));
        assertThat(screen.currentStepIndex()).isEqualTo(1);

        screen.onAppEvent(ctx, new AppEvent.FieldCancel("step"));

        assertThat(screen.currentStepIndex()).isEqualTo(0);
        verify(ctx, never()).pop();
    }

    @Test
    void existing_draft_resumes_on_first_incomplete_step() {
        when(session.netmailDraft()).thenReturn(new NetmailDraft("alice", "Subject", "Quoted body"));

        screen.onEnter(ctx);

        assertThat(screen.currentStepIndex()).isEqualTo(2);
    }

    @Test
    void abandoning_non_empty_draft_saves_it_to_session() {
        screen.onEnter(ctx);
        screen.onAppEvent(ctx, new AppEvent.FieldCommit("step", "alice"));
        screen.onAppEvent(ctx, new AppEvent.FieldCommit("step", "Subject"));
        screen.onAppEvent(ctx, new AppEvent.FieldCancel("step"));
        screen.onAppEvent(ctx, new AppEvent.FieldCancel("step"));

        verify(session).setNetmailDraft(new NetmailDraft("alice", "Subject", null));
    }
}
