package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.auth.UserRepository;
import io.aeyer.voidcore.social.AchievementRepository;
import io.aeyer.voidcore.social.ActivityEventRepository;
import io.aeyer.voidcore.social.WatchListRepository;
import io.aeyer.voidcore.ws.VoidCoreSession;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.BbsServices;
import io.aeyer.voidcore.ws.flow.screen.Navigator;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.protocol.ServerMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Punch-list bundle screen tests — one class for the four
 * read-mostly screens (watch list, activity feed, achievements,
 * audit). Covers guard bounces and Q-pop. Repos are Mockito-mocked.
 */
class PunchlistScreenTest {

    VoidCoreSession session;
    Navigator navigator;
    BbsServices services;
    BbsContext ctx;
    BbsContext sysopCtx;
    List<ServerMessage> sent;

    @BeforeEach
    void setUp() throws IOException {
        session = mock(VoidCoreSession.class);
        navigator = mock(Navigator.class);
        services = mock(BbsServices.class);
        sent = new ArrayList<>();
        org.mockito.Mockito.doAnswer(inv -> {
            sent.add(inv.getArgument(0));
            return null;
        }).when(session).send(any(ServerMessage.class));
        UserRepository.UserRow user = new UserRepository.UserRow(
                100, "SYSOP", "x", false, false);
        UserRepository.UserRow sysop = new UserRepository.UserRow(
                1, "SYSOP", "x", true, false);
        ctx = new BbsContext(session, user, navigator, services, null);
        sysopCtx = new BbsContext(session, sysop, navigator, services, null);
    }

    // === WatchListScreen =====================================================

    @Test
    void watchListPopsIfPreAuth() {
        WatchListRepository repo = mock(WatchListRepository.class);
        WatchListScreen scr = new WatchListScreen(repo);
        when(session.userId()).thenReturn(null);

        scr.onEnter(ctx);

        verify(navigator).pop(session);
    }

    @Test
    void watchListEmptyShowsNobodyMessage() {
        WatchListRepository repo = mock(WatchListRepository.class);
        when(repo.watchedBy(anyLong())).thenReturn(List.of());
        WatchListScreen scr = new WatchListScreen(repo);
        when(session.userId()).thenReturn(100L);

        scr.onEnter(ctx);

        // Should send a region.update; only Q in valid_keys.
        ServerMessage.InputPrompt prompt = sent.stream()
                .filter(m -> m instanceof ServerMessage.InputPrompt)
                .map(m -> (ServerMessage.InputPrompt) m)
                .findFirst().orElseThrow();
        assertThat(prompt.valid_keys()).isEqualTo("Q");
    }

    @Test
    void watchListNumberedSelectionPushesDocsResults() {
        WatchListRepository repo = mock(WatchListRepository.class);
        when(repo.watchedBy(anyLong())).thenReturn(List.of(
                new WatchListRepository.WatchedUser(7, "captaincrunch",
                        OffsetDateTime.now())));
        WatchListScreen scr = new WatchListScreen(repo);
        when(session.userId()).thenReturn(100L);

        scr.onKey(ctx, "1");

        // Filter set + DOCS_RESULTS pushed.
        verify(session).setDocsFilter("by=7");
        verify(navigator).push(session, Phase.DOCS_RESULTS);
    }

    @Test
    void watchListQPops() {
        WatchListScreen scr = new WatchListScreen(mock(WatchListRepository.class));
        scr.onKey(ctx, "Q");
        verify(navigator).pop(session);
    }

    // === ActivityFeedScreen ==================================================

    @Test
    void activityFeedPopsIfPreAuth() {
        ActivityEventRepository repo = mock(ActivityEventRepository.class);
        ActivityFeedScreen scr = new ActivityFeedScreen(repo);
        BbsContext anon = new BbsContext(session, null, navigator, services, null);

        scr.onEnter(anon);

        verify(navigator).pop(session);
    }

    @Test
    void activityFeedEmptyRendersAndPromptsQOnly() {
        ActivityEventRepository repo = mock(ActivityEventRepository.class);
        when(repo.recent(anyInt())).thenReturn(List.of());
        ActivityFeedScreen scr = new ActivityFeedScreen(repo);

        scr.onEnter(ctx);

        ServerMessage.InputPrompt prompt = sent.stream()
                .filter(m -> m instanceof ServerMessage.InputPrompt)
                .map(m -> (ServerMessage.InputPrompt) m)
                .findFirst().orElseThrow();
        assertThat(prompt.valid_keys()).isEqualTo("Q");
    }

    @Test
    void activityFeedQPops() {
        ActivityFeedScreen scr = new ActivityFeedScreen(
                mock(ActivityEventRepository.class));
        scr.onKey(ctx, "Q");
        verify(navigator).pop(session);
    }

    // === AchievementsScreen ==================================================

    @Test
    void achievementsPopsIfPreAuth() {
        AchievementRepository repo = mock(AchievementRepository.class);
        AchievementsScreen scr = new AchievementsScreen(repo);
        when(session.userId()).thenReturn(null);

        scr.onEnter(ctx);

        verify(navigator).pop(session);
    }

    @Test
    void achievementsRendersCatalogue() {
        AchievementRepository repo = mock(AchievementRepository.class);
        when(repo.awarded(anyLong())).thenReturn(List.of());
        when(repo.catalogue()).thenReturn(List.of(
                new AchievementRepository.Achievement(1, "first-login",
                        "First Call", "Logged in for the first time.",
                        5, "milestone", "bbs")));
        AchievementsScreen scr = new AchievementsScreen(repo);
        when(session.userId()).thenReturn(100L);

        scr.onEnter(ctx);

        boolean sawMain = sent.stream().anyMatch(m ->
                m instanceof ServerMessage.RegionUpdate ru
                        && "main".equals(ru.region()));
        assertThat(sawMain).isTrue();
    }

    @Test
    void achievementsQPops() {
        AchievementsScreen scr = new AchievementsScreen(
                mock(AchievementRepository.class));
        scr.onKey(ctx, "Q");
        verify(navigator).pop(session);
    }

    // === SysopAuditScreen ====================================================

    @Test
    void auditPopsForNonSysop() {
        SysopAuditScreen scr = new SysopAuditScreen(mock(org.jooq.DSLContext.class));

        scr.onEnter(ctx);  // non-sysop user

        verify(navigator).pop(session);
    }
}
