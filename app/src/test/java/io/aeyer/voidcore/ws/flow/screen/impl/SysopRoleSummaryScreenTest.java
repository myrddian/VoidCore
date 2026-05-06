package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.acl.AclPermission;
import io.aeyer.voidcore.acl.AclRepository;
import io.aeyer.voidcore.acl.AclResourceType;
import io.aeyer.voidcore.auth.RoleRepository;
import io.aeyer.voidcore.chat.ChatRepository;
import io.aeyer.voidcore.chat.ChatRoom;
import io.aeyer.voidcore.documents.DocumentKind;
import io.aeyer.voidcore.documents.DocumentRepository;
import io.aeyer.voidcore.documents.DocumentRow;
import io.aeyer.voidcore.documents.Status;
import io.aeyer.voidcore.documents.Visibility;
import io.aeyer.voidcore.messages.MessageBase;
import io.aeyer.voidcore.messages.MessageBaseRepository;
import io.aeyer.voidcore.polls.PollRepository;
import io.aeyer.voidcore.ws.VoidCoreSession;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.protocol.ServerMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SysopRoleSummaryScreenTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    RoleRepository roles;
    AclRepository acl;
    ChatRepository chat;
    MessageBaseRepository bases;
    DocumentRepository docs;
    PollRepository polls;
    BbsContext ctx;
    VoidCoreSession session;
    SysopRoleSummaryScreen screen;
    List<ServerMessage> sent;

    @BeforeEach
    void setUp() {
        roles = mock(RoleRepository.class);
        acl = mock(AclRepository.class);
        chat = mock(ChatRepository.class);
        bases = mock(MessageBaseRepository.class);
        docs = mock(DocumentRepository.class);
        polls = mock(PollRepository.class);
        ctx = mock(BbsContext.class);
        session = mock(VoidCoreSession.class);
        sent = new ArrayList<>();

        doAnswer(inv -> { sent.add(inv.getArgument(0)); return null; })
                .when(ctx).send(any(ServerMessage.class));

        when(ctx.isSysop()).thenReturn(true);
        when(ctx.session()).thenReturn(session);
        when(session.selectedSysopId()).thenReturn(5L);
        when(roles.findById(5L)).thenReturn(Optional.of(
                new RoleRepository.RoleRow(5L, "ADMIN", "Admin role")));
        when(acl.listRoleGrants(5L)).thenReturn(List.of(
                new AclRepository.GrantRow(AclResourceType.CHAT_ROOM, 1L, AclPermission.MANAGE),
                new AclRepository.GrantRow(AclResourceType.ONELINER_WALL, 1L, AclPermission.POST),
                new AclRepository.GrantRow(AclResourceType.ONELINER_WALL, 1L, AclPermission.VIEW),
                new AclRepository.GrantRow(AclResourceType.VOIDMAIL_SYSTEM, 1L, AclPermission.MANAGE),
                new AclRepository.GrantRow(AclResourceType.POLL, 0L, AclPermission.POST),
                new AclRepository.GrantRow(AclResourceType.POLL, 0L, AclPermission.VIEW),
                new AclRepository.GrantRow(AclResourceType.MESSAGE_BASE, 7L, AclPermission.POST),
                new AclRepository.GrantRow(AclResourceType.MESSAGE_BASE, 7L, AclPermission.VIEW),
                new AclRepository.GrantRow(AclResourceType.DOCUMENT, 42L, AclPermission.EDIT)));
        when(chat.findRoomById(1L)).thenReturn(Optional.of(
                new ChatRoom(1L, "general", "General", false, true, 1)));
        when(bases.findById(7L)).thenReturn(Optional.of(
                new MessageBase(7L, "general", "General", "", 1, false)));
        when(docs.findById(42L)).thenReturn(Optional.of(doc(42L, "article", "Welcome")));

        screen = new SysopRoleSummaryScreen(roles, acl, chat, bases, docs, polls);
    }

    @Test
    void onEnterRendersGroupedRoleGrantSummary() {
        screen.onEnter(ctx);

        String text = flatten(sent);
        assertThat(text).contains("chat rooms:");
        assertThat(text).contains("#general");
        assertThat(text).contains("one-liners:");
        assertThat(text).contains("global wall");
        assertThat(text).contains("voidmail:");
        assertThat(text).contains("global subsystem");
        assertThat(text).contains("polls:");
        assertThat(text).contains("poll hub");
        assertThat(text).contains("message boards:");
        assertThat(text).contains("general");
        assertThat(text).contains("view/post");
        assertThat(text).contains("documents:");
        assertThat(text).contains("article");
    }

    private static DocumentRow doc(long id, String typeSlug, String title) {
        ObjectNode fm = JSON.createObjectNode();
        return new DocumentRow(id, "doc-" + id, title, typeSlug, 1, 1,
                "body", fm, List.of(), 100L, Visibility.PUBLIC, Status.PUBLISHED,
                OffsetDateTime.now(), OffsetDateTime.now(), null, null, null);
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
