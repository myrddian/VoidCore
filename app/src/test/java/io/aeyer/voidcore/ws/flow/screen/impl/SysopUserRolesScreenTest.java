package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.auth.RoleRepository;
import io.aeyer.voidcore.auth.UserRepository;
import io.aeyer.voidcore.auth.UserRepository.UserRow;
import io.aeyer.voidcore.ws.VoidCoreSession;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.BbsServices;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SysopUserRolesScreenTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    UserRepository users;
    RoleRepository roles;
    BbsContext ctx;
    BbsServices services;
    VoidCoreSession session;
    SysopUserRolesScreen screen;
    UserRow user;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        roles = mock(RoleRepository.class);
        ctx = mock(BbsContext.class);
        services = mock(BbsServices.class);
        session = mock(VoidCoreSession.class);
        user = mock(UserRow.class);

        when(ctx.isSysop()).thenReturn(true);
        when(ctx.session()).thenReturn(session);
        when(ctx.services()).thenReturn(services);
        when(services.json()).thenReturn(JSON);
        when(session.selectedSysopId()).thenReturn(7L);
        when(user.id()).thenReturn(7L);
        when(user.handle()).thenReturn("alice");
        when(users.findById(7L)).thenReturn(Optional.of(user));
        when(roles.listAllRoles()).thenReturn(List.of(
                new RoleRepository.RoleRow(1L, "ADMIN", "Admin role"),
                new RoleRepository.RoleRow(2L, "MODERATOR", "Mod role")));

        screen = new SysopUserRolesScreen(users, roles);
    }

    @Test
    void number_assigns_unassigned_role() {
        when(roles.rolesForUser(7L)).thenReturn(List.of());

        screen.onKey(ctx, "1");

        verify(roles).assignRole(7L, 1L);
    }

    @Test
    void number_removes_assigned_role() {
        when(roles.rolesForUser(7L)).thenReturn(List.of(
                new RoleRepository.RoleRow(1L, "ADMIN", "Admin role")));

        screen.onKey(ctx, "1");

        verify(roles).removeRole(7L, 1L);
    }
}
