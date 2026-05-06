package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.documents.DocumentFilter;
import io.aeyer.voidcore.documents.DocumentKind;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.flow.screen.Screen;
import io.aeyer.voidcore.ws.flow.screen.ScreenComponent;
import io.aeyer.voidcore.ws.flow.screen.Transition;

@ScreenComponent
public class SysopRoleAnnouncementsScreen implements Screen {

    @Override public Phase phase() { return Phase.SYSOP_ROLE_ANNOUNCEMENTS; }
    @Override public String name() { return "sysop-role-announcements"; }

    @Override
    public java.util.List<String> topics(BbsContext ctx) {
        return java.util.List.of(io.aeyer.voidcore.ws.flow.view.DocumentView.TOPIC);
    }

    @Override
    public Transition onEnter(BbsContext ctx) {
        if (!ctx.isSysop()) { ctx.pop(); return Transition.None.INSTANCE; }
        if (ctx.session().selectedSysopId() == null) { ctx.pop(); return Transition.None.INSTANCE; }
        ctx.session().setSelectedSysopResourceId(null);
        var list = ctx.services().documents().findByFilter(
                DocumentFilter.empty().withKind(DocumentKind.ARTICLE),
                ctx.session(), 0, 9);
        return SysopRoleDocumentsScreen.render(ctx, list, "ROLE · ANNOUNCEMENTS", "role announcement:");
    }

    @Override
    public Transition onKey(BbsContext ctx, String key) {
        return SysopRoleDocumentsScreen.pickDocument(ctx, key,
                ctx.services().documents().findByFilter(
                        DocumentFilter.empty().withKind(DocumentKind.ARTICLE),
                        ctx.session(), 0, 9));
    }
}
