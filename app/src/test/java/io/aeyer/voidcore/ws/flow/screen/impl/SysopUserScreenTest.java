package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.auth.UserRepository;
import io.aeyer.voidcore.auth.UserRepository.UserRow;
import io.aeyer.voidcore.ws.VoidCoreSession;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.BbsServices;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.protocol.ServerMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SysopUserScreenTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    UserRepository users;
    BbsContext ctx;
    BbsServices services;
    VoidCoreSession session;
    SysopUserScreen screen;
    UserRow user;
    List<ServerMessage> sent;

    @BeforeEach
    void setUp() {
        users    = mock(UserRepository.class);
        ctx      = mock(BbsContext.class);
        services = mock(BbsServices.class);
        session  = mock(VoidCoreSession.class);
        sent     = new ArrayList<>();
        doAnswer(inv -> { sent.add(inv.getArgument(0)); return null; })
            .when(ctx).send(any(ServerMessage.class));

        when(ctx.isSysop()).thenReturn(true);
        when(ctx.session()).thenReturn(session);
        when(ctx.services()).thenReturn(services);
        when(session.selectedSysopId()).thenReturn(7L);
        when(services.json()).thenReturn(JSON);

        screen = new SysopUserScreen(users);
    }

    private void seedUser(boolean banned) {
        user = mock(UserRow.class);
        when(user.id()).thenReturn(7L);
        when(user.handle()).thenReturn("alice");
        when(user.isSysop()).thenReturn(false);
        when(user.isBanned()).thenReturn(banned);
        when(users.findById(7L)).thenReturn(Optional.of(user));
    }

    @Test
    void U_unbans_a_banned_user() {
        seedUser(true);
        screen.onEnter(ctx);
        screen.onKey(ctx, "U");
        verify(users).setBanned(eq(7L), eq(false), isNull());
    }

    @Test
    void B_pushes_ban_reason_phase() {
        seedUser(false);
        screen.onEnter(ctx);
        screen.onKey(ctx, "B");
        verify(ctx).push(Phase.SYSOP_USER_BAN_REASON);
    }

    @Test
    void R_pushes_reset_pw_phase() {
        seedUser(false);
        screen.onEnter(ctx);
        screen.onKey(ctx, "R");
        verify(ctx).push(Phase.SYSOP_USER_RESET_PW);
    }

    @Test
    void L_pushes_user_roles_phase() {
        seedUser(false);
        screen.onEnter(ctx);
        screen.onKey(ctx, "L");
        verify(ctx).push(Phase.SYSOP_USER_ROLES);
    }

    @Test
    void P_pushes_user_permissions_phase() {
        seedUser(false);
        screen.onEnter(ctx);
        screen.onKey(ctx, "P");
        verify(ctx).push(Phase.SYSOP_USER_PERMISSIONS);
    }

    @Test
    void Q_pops_screen() {
        seedUser(false);
        screen.onEnter(ctx);
        screen.onKey(ctx, "Q");
        verify(ctx).pop();
    }
}
