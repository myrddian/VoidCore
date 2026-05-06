package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.acl.AclPermission;
import io.aeyer.voidcore.acl.AclResourceType;
import io.aeyer.voidcore.acl.AclService;
import io.aeyer.voidcore.chat.ChatRepository;
import io.aeyer.voidcore.documents.DocumentKind;
import io.aeyer.voidcore.ws.flow.Frames;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.flow.screen.Screen;
import io.aeyer.voidcore.ws.flow.screen.ScreenComponent;
import io.aeyer.voidcore.ws.flow.screen.Transition;
import io.aeyer.voidcore.ws.protocol.ServerMessage.InputPrompt;

import java.util.List;

/** Sysop tools menu — gateway to user / announcement / release / chat / broadcast tools. */
@ScreenComponent
public class SysopMenuScreen implements Screen {

    private final AclService acl;
    private final ChatRepository chat;

    public SysopMenuScreen(AclService acl, ChatRepository chat) {
        this.acl = acl;
        this.chat = chat;
    }

    @Override public Phase phase() { return Phase.SYSOP_MENU; }
    @Override public String name() { return "sysop-menu"; }

    @Override
    public Transition onEnter(BbsContext ctx) {
        if (!ctx.isSysop() && !acl.hasAnyManageAccess(ctx.session())) { ctx.pop(); return Transition.None.INSTANCE; }
        ctx.persistCurrentScreen("{\"kind\":\"sysop_menu\"}");
        java.util.ArrayList<String> lines = new java.util.ArrayList<>(List.of(
                "",
                "  == " + (ctx.isSysop() ? "SYSOP TOOLS" : "OPERATOR TOOLS") + " ==",
                ""));
        StringBuilder valid = new StringBuilder("Q");
        if (ctx.isSysop()) {
            lines.add("  [U] users        list / ban / unban / reset password");
            valid.append('U');
        }
        if (canManageAnnouncements(ctx)) {
            lines.add("  [B] announcements list / pin / delete");
            valid.append('B');
        }
        if (canManageReleases(ctx)) {
            lines.add("  [F] releases     edit external url");
            valid.append('F');
        }
        if (canManageChatRooms(ctx)) {
            lines.add("  [C] chat rooms   disable / re-enable");
            valid.append('C');
        }
        lines.add("  [P] polls        open / close managed polls");
        valid.append('P');
        if (ctx.isSysop()) {
            lines.add("  [E] screens      enable / disable main surfaces");
            valid.append('E');
            lines.add("  [R] roles        create / assign / asset grants");
            lines.add("  [A] audit log    last 50 sysop actions");
            lines.add("  [X] broadcast    push system notification to all sessions");
            valid.append("RAX");
        }
        lines.add("");
        lines.add("  [Q] back to main menu");
        ctx.send(Frames.update("main", 70, Frames.textRows(lines, "default")));
        ctx.send(new InputPrompt("keystroke", "ops:", null, valid.toString(), null));
        return Transition.None.INSTANCE;
    }

    @Override
    public Transition onKey(BbsContext ctx, String key) {
        switch (key) {
            case "U" -> { if (ctx.isSysop()) ctx.push(Phase.SYSOP_USERS); }
            case "B" -> { if (canManageAnnouncements(ctx)) ctx.push(Phase.SYSOP_BULLETINS); }
            case "F" -> { if (canManageReleases(ctx)) ctx.push(Phase.SYSOP_RELEASES); }
            case "C" -> { if (canManageChatRooms(ctx)) ctx.push(Phase.SYSOP_CHAT_ROOMS); }
            case "P" -> ctx.push(Phase.POLLS_LIST);
            case "E" -> { if (ctx.isSysop()) ctx.push(Phase.SYSOP_SCREEN_TOGGLES); }
            case "R" -> { if (ctx.isSysop()) ctx.push(Phase.SYSOP_ROLES); }
            case "A" -> { if (ctx.isSysop()) ctx.push(Phase.SYSOP_AUDIT); }
            case "X" -> { if (ctx.isSysop()) ctx.push(Phase.SYSOP_BROADCAST); }
            case "Q" -> ctx.pop();
            default -> { /* ignored */ }
        }
        return Transition.None.INSTANCE;
    }

    private boolean canManageAnnouncements(BbsContext ctx) {
        return ctx.isSysop() || ctx.services().documents().list().stream()
                .filter(doc -> DocumentKind.ARTICLE.wireValue().equals(doc.typeSlug()))
                .anyMatch(doc -> acl.can(ctx.session(), AclResourceType.DOCUMENT, doc.id(), AclPermission.MANAGE));
    }

    private boolean canManageReleases(BbsContext ctx) {
        return ctx.isSysop() || ctx.services().documents().list().stream()
                .filter(doc -> DocumentKind.RELEASE.wireValue().equals(doc.typeSlug()))
                .anyMatch(doc -> acl.can(ctx.session(), AclResourceType.DOCUMENT, doc.id(), AclPermission.MANAGE));
    }

    private boolean canManageChatRooms(BbsContext ctx) {
        return ctx.isSysop() || chat.listAllRooms().stream()
                .anyMatch(room -> acl.can(ctx.session(), AclResourceType.CHAT_ROOM, room.id(), AclPermission.MANAGE));
    }
}
