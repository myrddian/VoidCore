package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.auth.RoleRepository;
import io.aeyer.voidcore.ws.VoidCoreSession;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.protocol.ServerMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SysopRolesScreenTest {

    RoleRepository roles;
    BbsContext ctx;
    VoidCoreSession session;
    SysopRolesScreen screen;
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
        when(roles.listAllRoles()).thenReturn(List.of(
                new RoleRepository.RoleRow(1L, "ADMIN", "Admin role"),
                new RoleRepository.RoleRow(2L, "MODERATOR", "Moderator role")));

        screen = new SysopRolesScreen(roles);
    }

    @Test
    void onEnterShowsBuiltInRolePolicySummary() {
        screen.onEnter(ctx);

        ServerMessage.RegionUpdate main = sent.stream()
                .filter(m -> m instanceof ServerMessage.RegionUpdate ru && "main".equals(ru.region()))
                .map(m -> (ServerMessage.RegionUpdate) m)
                .findFirst()
                .orElseThrow();
        String text = flatten(main);
        assertThat(text).contains("ADMIN: broad delegated control");
        assertThat(text).contains("MODERATOR: community control");
    }

    private static String flatten(ServerMessage.RegionUpdate update) {
        StringBuilder sb = new StringBuilder();
        for (ServerMessage.Row row : update.content()) {
            for (ServerMessage.Span span : row.spans()) {
                sb.append(span.text());
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
