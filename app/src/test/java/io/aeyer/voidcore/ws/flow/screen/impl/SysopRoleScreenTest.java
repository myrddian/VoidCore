package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.auth.RoleRepository;
import io.aeyer.voidcore.ws.VoidCoreSession;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.protocol.ServerMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SysopRoleScreenTest {

    RoleRepository roles;
    BbsContext ctx;
    VoidCoreSession session;
    SysopRoleScreen screen;
    List<ServerMessage> sent;

    @BeforeEach
    void setUp() {
        roles = mock(RoleRepository.class);
        ctx = mock(BbsContext.class);
        session = mock(VoidCoreSession.class);
        sent = new ArrayList<>();

        doAnswer(inv -> { sent.add(inv.getArgument(0)); return null; })
                .when(ctx).send(any(ServerMessage.class));

        when(ctx.isSysop()).thenReturn(true);
        when(ctx.session()).thenReturn(session);

        screen = new SysopRoleScreen(roles);
    }

    @Test
    void adminRoleShowsDefaultPolicyDetail() {
        when(session.selectedSysopId()).thenReturn(1L);
        when(roles.findById(1L)).thenReturn(Optional.of(
                new RoleRepository.RoleRow(1L, "ADMIN", "Default admin role")));

        screen.onEnter(ctx);

        String text = flatten(sent);
        assertThat(text).contains("default policy:");
        assertThat(text).contains("manage chat rooms");
        assertThat(text).contains("manage the one-liners wall");
        assertThat(text).contains("manage polls");
        assertThat(text).contains("manage VoidMail access policy");
        assertThat(text).contains("manage message boards");
        assertThat(text).contains("manage documents");
    }

    @Test
    void moderatorRoleShowsAnnouncementPolicyDetail() {
        when(session.selectedSysopId()).thenReturn(2L);
        when(roles.findById(2L)).thenReturn(Optional.of(
                new RoleRepository.RoleRow(2L, "MODERATOR", "Default moderator role")));

        screen.onEnter(ctx);

        String text = flatten(sent);
        assertThat(text).contains("manage chat rooms");
        assertThat(text).contains("manage the one-liners wall");
        assertThat(text).contains("manage polls");
        assertThat(text).contains("manage message boards");
        assertThat(text).contains("view/edit announcements");
    }

    @Test
    void onKeySPushesRoleSummary() {
        screen.onKey(ctx, "S");
        verify(ctx).push(Phase.SYSOP_ROLE_SUMMARY);
    }

    @Test
    void onKeyOPushesRoleOneliners() {
        screen.onKey(ctx, "O");
        verify(ctx).push(Phase.SYSOP_ROLE_ONELINERS);
    }

    @Test
    void onKeyVPushesRoleVoidmail() {
        screen.onKey(ctx, "V");
        verify(ctx).push(Phase.SYSOP_ROLE_VOIDMAIL);
    }

    @Test
    void onKeyPPushesRolePolls() {
        screen.onKey(ctx, "P");
        verify(ctx).push(Phase.SYSOP_ROLE_POLLS);
    }

    private static String flatten(List<ServerMessage> messages) {
        ServerMessage.RegionUpdate main = messages.stream()
                .filter(m -> m instanceof ServerMessage.RegionUpdate ru && "main".equals(ru.region()))
                .map(m -> (ServerMessage.RegionUpdate) m)
                .findFirst()
                .orElseThrow();
        StringBuilder sb = new StringBuilder();
        for (ServerMessage.Row row : main.content()) {
            for (ServerMessage.Span span : row.spans()) {
                sb.append(span.text());
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
