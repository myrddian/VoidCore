package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.acl.AclPermission;
import io.aeyer.voidcore.acl.AclResourceType;
import io.aeyer.voidcore.acl.AclService;
import io.aeyer.voidcore.instance.InstanceFeature;
import io.aeyer.voidcore.instance.InstanceFeatureService;
import io.aeyer.voidcore.messages.MessageBase;
import io.aeyer.voidcore.messages.MessageBaseRepository;
import io.aeyer.voidcore.messages.MessageBaseRepository.BaseWithUnread;
import io.aeyer.voidcore.ws.VoidCoreSession;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.BbsServices;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.flow.layout.Element;
import io.aeyer.voidcore.ws.flow.ui.AppEvent;
import io.aeyer.voidcore.ws.protocol.ServerMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BasesListScreenTest {

    MessageBaseRepository repo;
    AclService acl;
    BbsContext ctx;
    BbsServices services;
    VoidCoreSession session;
    BasesListScreen screen;
    List<ServerMessage> sent;

    @BeforeEach
    void setUp() {
        repo = mock(MessageBaseRepository.class);
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
        when(features.enabled(InstanceFeature.MESSAGE_BOARD)).thenReturn(true);
        when(session.userId()).thenReturn(7L);
        when(repo.listAllWithUnread(7L)).thenReturn(List.of(
                new BaseWithUnread(new MessageBase(1L, "general", "General", "", 1, false), 2),
                new BaseWithUnread(new MessageBase(2L, "staff", "Staff", "", 2, false), 1)));
        when(acl.can(session, AclResourceType.MESSAGE_BASE, 1L, AclPermission.VIEW)).thenReturn(true);
        when(acl.can(session, AclResourceType.MESSAGE_BASE, 2L, AclPermission.VIEW)).thenReturn(false);

        screen = new BasesListScreen(repo, acl);
    }

    /** The list the screen actually composed, or null if it rendered none. */
    private Element.ListView renderedList() {
        return sent.stream()
                .filter(ServerMessage.RegionUpdate.class::isInstance)
                .map(ServerMessage.RegionUpdate.class::cast)
                .map(ServerMessage.RegionUpdate::tree)
                .filter(java.util.Objects::nonNull)
                .map(BasesListScreenTest::findList)
                .filter(java.util.Objects::nonNull)
                .findFirst().orElse(null);
    }

    private static Element.ListView findList(Element el) {
        return switch (el) {
            case Element.ListView lv -> lv;
            case Element.VStack v -> v.children().stream()
                    .map(BasesListScreenTest::findList)
                    .filter(java.util.Objects::nonNull).findFirst().orElse(null);
            default -> null;
        };
    }

    @Test
    void onEnterFiltersBasesByViewAcl() {
        screen.onEnter(ctx);

        verify(ctx).persistCurrentScreen("{\"kind\":\"bases\"}");
        verify(ctx).send(any(ServerMessage.InputPrompt.class));

        // Assert on what was composed rather than counting sends: the screen
        // is a ScreenApp now, so onEnter also minimises the banner.
        Element.ListView list = renderedList();
        assertThat(list).isNotNull();
        assertThat(list.items()).hasSize(1);
        assertThat(list.items().get(0).id()).isEqualTo("1");
        assertThat(list.items().get(0).label()).contains("general");
    }

    @Test
    void selectingAnItemOpensThatBoard() {
        screen.onEnter(ctx);

        screen.onAppEvent(ctx, new AppEvent.ListSelected("bases", "1"));

        verify(session).setSelectedBaseId(1L);
        verify(ctx).push(Phase.THREADS_LIST);
    }

    @Test
    void ignoresASelectionTheUserMayNotView() {
        // The id arrives off the wire; a client naming a filtered-out board
        // must not get in through the list path.
        screen.onEnter(ctx);

        screen.onAppEvent(ctx, new AppEvent.ListSelected("bases", "2"));

        verify(session, org.mockito.Mockito.never()).setSelectedBaseId(2L);
        verify(ctx, org.mockito.Mockito.never()).push(Phase.THREADS_LIST);
    }

    @Test
    void ignoresASelectionFromAnotherWidget() {
        screen.onEnter(ctx);

        screen.onAppEvent(ctx, new AppEvent.ListSelected("something-else", "1"));

        verify(ctx, org.mockito.Mockito.never()).push(Phase.THREADS_LIST);
    }

    @Test
    void doesNotRepaintItselfOverTheScreenItOpened() {
        // ScreenApp.onAppEvent recomposes after the handler runs. Pushing
        // with a bare ctx.push() therefore paints this list back over the
        // threads screen — the user gets the new prompt above the old body.
        // Caught in a browser, not by the earlier tests.
        screen.onEnter(ctx);
        int framesAfterEnter = sent.size();

        screen.onAppEvent(ctx, new AppEvent.ListSelected("bases", "1"));

        long repaints = sent.stream().skip(framesAfterEnter)
                .filter(ServerMessage.RegionUpdate.class::isInstance)
                .count();
        assertThat(repaints).isZero();
    }

    @Test
    void numericSelectionStillWorksAlongsideTheList() {
        screen.onEnter(ctx);
        screen.onKey(ctx, "1");

        verify(session).setSelectedBaseId(1L);
        verify(ctx).push(Phase.THREADS_LIST);
    }
}
