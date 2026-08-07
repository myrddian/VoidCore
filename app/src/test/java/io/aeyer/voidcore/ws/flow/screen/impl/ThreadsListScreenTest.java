package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.acl.AclPermission;
import io.aeyer.voidcore.acl.AclResourceType;
import io.aeyer.voidcore.acl.AclService;
import io.aeyer.voidcore.instance.InstanceFeature;
import io.aeyer.voidcore.instance.InstanceFeatureService;
import io.aeyer.voidcore.messages.BoardThread;
import io.aeyer.voidcore.messages.MessageBase;
import io.aeyer.voidcore.messages.MessageBaseRepository;
import io.aeyer.voidcore.messages.ThreadRepository;
import io.aeyer.voidcore.messages.ThreadRepository.ThreadWithUnread;
import io.aeyer.voidcore.ws.VoidCoreSession;
import io.aeyer.voidcore.ws.flow.layout.Element;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.BbsServices;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.flow.ui.AppEvent;
import io.aeyer.voidcore.ws.protocol.ServerMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Threads list on the {@code list} element — second adopter after the
 * message board. The screen had no test before this migration.
 */
class ThreadsListScreenTest {

    MessageBaseRepository bases;
    ThreadRepository threads;
    AclService acl;
    BbsContext ctx;
    VoidCoreSession session;
    ThreadsListScreen screen;
    List<ServerMessage> sent;

    private static final long BASE_ID = 5L;

    @BeforeEach
    void setUp() {
        bases = mock(MessageBaseRepository.class);
        threads = mock(ThreadRepository.class);
        acl = mock(AclService.class);
        ctx = mock(BbsContext.class);
        session = mock(VoidCoreSession.class);
        BbsServices services = mock(BbsServices.class);
        sent = new ArrayList<>();

        doAnswer(inv -> { sent.add(inv.getArgument(0)); return null; })
                .when(ctx).send(any(ServerMessage.class));
        when(ctx.session()).thenReturn(session);
        when(ctx.services()).thenReturn(services);
        InstanceFeatureService features = mock(InstanceFeatureService.class);
        when(services.instanceFeatures()).thenReturn(features);
        when(features.enabled(InstanceFeature.MESSAGE_BOARD)).thenReturn(true);

        when(session.userId()).thenReturn(7L);
        when(session.selectedBaseId()).thenReturn(BASE_ID);
        when(bases.findById(BASE_ID)).thenReturn(Optional.of(
                new MessageBase(BASE_ID, "general", "General", "", 1, false)));
        when(acl.can(session, AclResourceType.MESSAGE_BASE, BASE_ID, AclPermission.VIEW)).thenReturn(true);
        when(acl.can(session, AclResourceType.MESSAGE_BASE, BASE_ID, AclPermission.POST)).thenReturn(true);
        when(threads.listInBase(BASE_ID, 7L)).thenReturn(List.of(
                thread(11L, "First light", false, false),
                thread(12L, "Pinned notice", true, false)));

        screen = new ThreadsListScreen(bases, threads, acl);
    }

    private ThreadWithUnread thread(long id, boolean pinned, boolean unread) {
        return thread(id, "subject " + id, pinned, unread);
    }

    private ThreadWithUnread thread(long id, String subject, boolean pinned, boolean unread) {
        return new ThreadWithUnread(new BoardThread(
                id, BASE_ID, subject, "alice",
                OffsetDateTime.parse("2026-01-01T00:00:00Z"),
                OffsetDateTime.parse("2026-01-02T00:00:00Z"),
                3, pinned, false), unread);
    }

    private Element.ListView renderedList() {
        return sent.stream()
                .filter(ServerMessage.RegionUpdate.class::isInstance)
                .map(ServerMessage.RegionUpdate.class::cast)
                .map(ServerMessage.RegionUpdate::tree)
                .filter(Objects::nonNull)
                .map(ThreadsListScreenTest::findList)
                .filter(Objects::nonNull)
                .findFirst().orElse(null);
    }

    private static Element.ListView findList(Element el) {
        return switch (el) {
            case Element.ListView lv -> lv;
            case Element.VStack v -> v.children().stream()
                    .map(ThreadsListScreenTest::findList)
                    .filter(Objects::nonNull).findFirst().orElse(null);
            default -> null;
        };
    }

    @Test
    void composesTheThreadsAsAList() {
        screen.onEnter(ctx);

        Element.ListView list = renderedList();
        assertThat(list).isNotNull();
        assertThat(list.items()).hasSize(2);
        assertThat(list.items().get(0).id()).isEqualTo("11");
        assertThat(list.items().get(0).label()).contains("First light");
    }

    @Test
    void keepsThePinnedMarker() {
        screen.onEnter(ctx);

        // [*] pinned carried meaning in the row layout; it must survive the
        // move to a list row.
        assertThat(renderedList().items().get(1).label()).contains("[*]");
    }

    @Test
    void selectingAThreadOpensIt() {
        screen.onEnter(ctx);

        screen.onAppEvent(ctx, new AppEvent.ListSelected("threads", "11"));

        verify(session).setSelectedThreadId(11L);
        verify(ctx).push(Phase.THREAD_VIEW);
    }

    @Test
    void ignoresAThreadThatIsNotInThisBase() {
        // The id arrives off the wire; only ids in the visible list count.
        screen.onEnter(ctx);

        screen.onAppEvent(ctx, new AppEvent.ListSelected("threads", "999"));

        verify(session, never()).setSelectedThreadId(999L);
        verify(ctx, never()).push(Phase.THREAD_VIEW);
    }

    @Test
    void doesNotRepaintItselfOverTheThreadItOpened() {
        // ScreenApp recomposes after the event handler; without pushAndExit
        // this list paints back over the thread view it just pushed.
        screen.onEnter(ctx);
        int framesAfterEnter = sent.size();

        screen.onAppEvent(ctx, new AppEvent.ListSelected("threads", "11"));

        long repaints = sent.stream().skip(framesAfterEnter)
                .filter(ServerMessage.RegionUpdate.class::isInstance)
                .count();
        assertThat(repaints).isZero();
    }

    @Test
    void numericSelectionStillWorks() {
        screen.onEnter(ctx);
        screen.onKey(ctx, "1");

        verify(session).setSelectedThreadId(11L);
        verify(ctx).push(Phase.THREAD_VIEW);
    }

    @Test
    void newThreadIsRefusedOnAReadOnlyBoard() {
        when(acl.can(session, AclResourceType.MESSAGE_BASE, BASE_ID, AclPermission.POST)).thenReturn(false);
        screen.onEnter(ctx);

        screen.onKey(ctx, "N");

        verify(ctx, never()).push(Phase.COMPOSE_THREAD);
    }

    @Test
    void popsWhenTheBaseIsNoLongerReadable() {
        when(acl.can(session, AclResourceType.MESSAGE_BASE, BASE_ID, AclPermission.VIEW)).thenReturn(false);

        screen.onEnter(ctx);

        verify(session).setSelectedBaseId(null);
        verify(ctx).pop();
    }
}
