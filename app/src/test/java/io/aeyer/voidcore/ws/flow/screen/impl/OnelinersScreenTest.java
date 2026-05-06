package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.acl.AclPermission;
import io.aeyer.voidcore.acl.AclResourceType;
import io.aeyer.voidcore.acl.AclService;
import io.aeyer.voidcore.instance.InstanceFeature;
import io.aeyer.voidcore.instance.InstanceFeatureService;
import io.aeyer.voidcore.auth.RateLimiter;
import io.aeyer.voidcore.oneliners.Oneliner;
import io.aeyer.voidcore.oneliners.OnelinerRepository;
import io.aeyer.voidcore.ws.VoidCoreSession;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.BbsServices;
import io.aeyer.voidcore.ws.protocol.ServerMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OnelinersScreenTest {

    OnelinerRepository repo;
    AclService acl;
    RateLimiter rateLimiter;
    ObjectProvider<io.aeyer.voidcore.social.ReactionRepository> reactions;
    BbsContext ctx;
    BbsServices services;
    VoidCoreSession session;
    OnelinersScreen screen;
    List<ServerMessage> sent;

    @BeforeEach
    void setUp() {
        repo = mock(OnelinerRepository.class);
        acl = mock(AclService.class);
        rateLimiter = mock(RateLimiter.class);
        reactions = mock(ObjectProvider.class);
        ctx = mock(BbsContext.class);
        services = mock(BbsServices.class);
        session = mock(VoidCoreSession.class);
        sent = new ArrayList<>();

        doAnswer(inv -> { sent.add(inv.getArgument(0)); return null; })
                .when(ctx).send(any(ServerMessage.class));

        when(reactions.getIfAvailable()).thenReturn(null);
        when(ctx.session()).thenReturn(session);
        when(ctx.services()).thenReturn(services);
        InstanceFeatureService features = mock(InstanceFeatureService.class);
        when(services.instanceFeatures()).thenReturn(features);
        when(features.enabled(InstanceFeature.ONELINERS)).thenReturn(true);
        when(session.userId()).thenReturn(7L);
        when(repo.recent(40)).thenReturn(List.of(
                new Oneliner(1L, "enzo", "hello", OffsetDateTime.now())));

        screen = new OnelinersScreen(repo, acl, rateLimiter, reactions);
    }

    @Test
    void onEnter_pops_when_view_is_denied() {
        when(acl.can(session, AclResourceType.ONELINER_WALL, OnelinersScreen.WALL_ID, AclPermission.VIEW))
                .thenReturn(false);

        screen.onEnter(ctx);

        verify(ctx).pop();
        assertThat(flatten(sent)).contains("you do not have access to the one-liners wall");
    }

    @Test
    void onLine_refuses_post_when_post_is_denied() {
        when(acl.can(session, AclResourceType.ONELINER_WALL, OnelinersScreen.WALL_ID, AclPermission.POST))
                .thenReturn(false);

        screen.onLine(ctx, "hello world");

        verify(repo, never()).insert(anyLong(), anyString());
        assertThat(flatten(sent)).contains("you do not have permission to post on the wall");
    }

    @Test
    void onEvent_pops_when_access_is_lost() {
        when(acl.can(session, AclResourceType.ONELINER_WALL, OnelinersScreen.WALL_ID, AclPermission.VIEW))
                .thenReturn(false);

        screen.onEvent(ctx, OnelinersScreen.TOPIC);

        verify(ctx).pop();
        assertThat(flatten(sent)).contains("you no longer have access to the one-liners wall");
    }

    private static String flatten(List<ServerMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (ServerMessage message : messages) {
            if (message instanceof ServerMessage.RegionNotify n) {
                for (ServerMessage.Row row : n.content()) {
                    for (ServerMessage.Span span : row.spans()) {
                        sb.append(span.text());
                    }
                    sb.append('\n');
                }
            } else if (message instanceof ServerMessage.RegionUpdate ru) {
                for (ServerMessage.Row row : ru.content()) {
                    for (ServerMessage.Span span : row.spans()) {
                        sb.append(span.text());
                    }
                    sb.append('\n');
                }
            }
        }
        return sb.toString();
    }
}
