package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.auth.RoleRepository;
import io.aeyer.voidcore.ws.VoidCoreSession;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.BbsServices;
import io.aeyer.voidcore.ws.flow.ui.AppEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SysopRoleNewScreenTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    RoleRepository roles;
    BbsContext ctx;
    BbsServices services;
    VoidCoreSession session;
    SysopRoleNewScreen screen;

    @BeforeEach
    void setUp() {
        roles = mock(RoleRepository.class);
        ctx = mock(BbsContext.class);
        services = mock(BbsServices.class);
        session = mock(VoidCoreSession.class);

        when(ctx.isSysop()).thenReturn(true);
        when(ctx.session()).thenReturn(session);
        when(ctx.services()).thenReturn(services);
        when(services.json()).thenReturn(JSON);
        when(roles.findByName("ADMIN_GAMES")).thenReturn(Optional.empty());
        when(roles.createRole("ADMIN_GAMES", "Games operator")).thenReturn(8L);

        screen = new SysopRoleNewScreen(roles);
    }

    @Test
    void wizard_creates_role() {
        screen.onEnter(ctx);
        screen.onAppEvent(ctx, new AppEvent.FieldCommit("step", "admin_games"));
        screen.onAppEvent(ctx, new AppEvent.FieldCommit("step", "Games operator"));

        verify(roles).createRole("ADMIN_GAMES", "Games operator");
        verify(ctx).pop();
    }

    @Test
    void duplicate_name_rejects_first_step() {
        when(roles.findByName("ADMIN")).thenReturn(Optional.of(
                new RoleRepository.RoleRow(1L, "ADMIN", "Admin role")));

        screen.onEnter(ctx);
        screen.onAppEvent(ctx, new AppEvent.FieldCommit("step", "admin"));

        assertThat(screen.currentStepIndex()).isEqualTo(0);
    }
}
