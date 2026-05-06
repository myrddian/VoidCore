package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.acl.AclPermission;
import io.aeyer.voidcore.acl.AclResourceType;
import io.aeyer.voidcore.acl.AclService;
import io.aeyer.voidcore.documents.DocumentRepository;
import io.aeyer.voidcore.documents.DocumentKind;
import io.aeyer.voidcore.documents.DocumentRow;
import io.aeyer.voidcore.ws.flow.Frames;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.flow.screen.Screen;
import io.aeyer.voidcore.ws.flow.screen.ScreenComponent;
import io.aeyer.voidcore.ws.flow.screen.ScreenText;
import io.aeyer.voidcore.ws.flow.screen.Transition;
import io.aeyer.voidcore.ws.flow.view.DocumentView;
import io.aeyer.voidcore.ws.protocol.ServerMessage.InputPrompt;
import io.aeyer.voidcore.ws.protocol.ServerMessage.Row;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Sysop · announcements — list, [N]ew / [P]in / [D]elete.
 *
 * <p>[P] and [D] are two-step actions: first press the letter
 * (arms a pending action via {@code selectedSysopId} sentinel
 * values -1/-2), then a digit picks the bulletin. Same UX as
 * before; logic moved here.
 */
@ScreenComponent
public class SysopBulletinsScreen implements Screen {

    private static final long PEND_PIN = -1L;
    private static final long PEND_DELETE = -2L;
    private static final long PEND_EDIT = -3L;

    private final DocumentRepository docs;
    private final AclService acl;

    public SysopBulletinsScreen(DocumentRepository docs, AclService acl) {
        this.docs = docs;
        this.acl = acl;
    }

    @Override public Phase phase() { return Phase.SYSOP_BULLETINS; }
    @Override public String name() { return "sysop-bulletins"; }

    @Override
    public List<String> topics(BbsContext ctx) {
        return List.of(DocumentView.TOPIC);
    }

    @Override
    public Transition onEnter(BbsContext ctx) {
        if (!canEnter(ctx)) { ctx.pop(); return Transition.None.INSTANCE; }
        ctx.persistCurrentScreen("{\"kind\":\"sysop_bulletins\"}");
        List<DocumentRow> list = manageableAnnouncements(ctx);
        ArrayList<Row> rows = new ArrayList<>();
        rows.add(Frames.colored(0, "  == SYSOP · ANNOUNCEMENTS ==   " + list.size(), "bright_yellow"));
        rows.add(Frames.blank(1));
        int rowN = 2;
        for (int i = 0; i < list.size(); i++) {
            DocumentRow b = list.get(i);
            boolean pinned = isPinned(b);
            String date = b.createdAt() == null ? "" : b.createdAt().toLocalDate().toString();
            rows.add(Frames.row(rowN++,
                    Frames.span("  [", "grey"),
                    Frames.span(String.valueOf(i + 1), "bright_yellow", true),
                    Frames.span("] ", "grey"),
                    Frames.span(pinned ? "[*] " : "    ",
                            pinned ? "bright_red" : "grey"),
                    Frames.span(ScreenText.padRight(ScreenText.truncate(b.title(), 44), 46), "default"),
                    Frames.span(date, "dark_grey")));
        }
        if (list.isEmpty()) {
            rows.add(Frames.colored(rowN++, "  (no announcements yet)", "dark_grey"));
        }
        rows.add(Frames.blank(rowN++));
        rows.add(Frames.row(rowN,
                Frames.span("  [", "grey"),
                Frames.span(canCreate(ctx) ? "N" : "-", canCreate(ctx) ? "bright_yellow" : "dark_grey", canCreate(ctx)),
                Frames.span("] new   pick number to ", "grey"),
                Frames.span("[E]", "bright_yellow", true),
                Frames.span("dit / ", "grey"),
                Frames.span("[P]", "bright_yellow", true),
                Frames.span("in / ", "grey"),
                Frames.span("[D]", "bright_yellow", true),
                Frames.span("elete   [", "grey"),
                Frames.span("Q", "bright_yellow", true),
                Frames.span("] back", "grey")));
        ctx.send(Frames.update("main", 73, rows));
        StringBuilder valid = new StringBuilder("EPDQ");
        if (canCreate(ctx)) valid.append('N');
        for (int i = 0; i < list.size(); i++) valid.append(i + 1);
        ctx.send(new InputPrompt("keystroke", "announcement:", null, valid.toString(), null));
        return Transition.None.INSTANCE;
    }

    @Override
    public Transition onKey(BbsContext ctx, String k) {
        if (!canEnter(ctx)) return Transition.None.INSTANCE;
        switch (k) {
            case "Q" -> {
                ctx.session().setSelectedSysopId(null);
                ctx.pop();
            }
            case "N" -> {
                if (!canCreate(ctx)) return Transition.None.INSTANCE;
                ctx.session().setSelectedSysopId(null);
                ctx.push(Phase.SYSOP_BULLETIN_NEW);
            }
            case "E" -> {
                ctx.send(Frames.notify("notifications",
                        "pick the announcement number to edit", "info", 2500));
                ctx.session().setSelectedSysopId(PEND_EDIT);
            }
            case "P" -> {
                ctx.send(Frames.notify("notifications",
                        "pick the announcement number to pin/unpin", "info", 2500));
                ctx.session().setSelectedSysopId(PEND_PIN);
            }
            case "D" -> {
                ctx.send(Frames.notify("notifications",
                        "pick the announcement number to delete", "info", 2500));
                ctx.session().setSelectedSysopId(PEND_DELETE);
            }
            default -> {
                if (k.length() == 1 && Character.isDigit(k.charAt(0))) {
                    int idx = Character.digit(k.charAt(0), 10);
                    List<DocumentRow> list = manageableAnnouncements(ctx);
                    if (idx < 1 || idx > list.size()) return Transition.None.INSTANCE;
                    DocumentRow b = list.get(idx - 1);
                    boolean pinned = isPinned(b);
                    Long pending = ctx.session().selectedSysopId();
                    if (pending != null && pending == PEND_PIN) {
                        docs.updateFrontmatterBoolean(b.id(), "pinned", !pinned);
                        ctx.publish(DocumentView.TOPIC);
                        ctx.audit(pinned ? "unpin_bulletin" : "pin_bulletin",
                                ctx.services().json().createObjectNode().put("bulletin_id", b.id()));
                        ctx.send(Frames.notify("notifications",
                                (pinned ? "unpinned" : "pinned") + ": " + b.title(),
                                "info", 2500));
                        ctx.session().setSelectedSysopId(null);
                        onEnter(ctx);
                    } else if (pending != null && pending == PEND_DELETE) {
                        docs.delete(b.id());
                        ctx.publish(DocumentView.TOPIC);
                        ctx.audit("delete_bulletin",
                                ctx.services().json().createObjectNode()
                                        .put("bulletin_id", b.id()).put("title", b.title()));
                        ctx.send(Frames.notify("notifications",
                                "deleted: " + b.title(), "warn", 3000));
                        ctx.session().setSelectedSysopId(null);
                        onEnter(ctx);
                    } else if (pending != null && pending == PEND_EDIT) {
                        ctx.session().setSelectedSysopId(b.id());
                        ctx.push(Phase.SYSOP_BULLETIN_EDIT);
                    }
                }
            }
        }
        return Transition.None.INSTANCE;
    }

    private List<DocumentRow> manageableAnnouncements(BbsContext ctx) {
        return ctx.services().documents().list().stream()
                .filter(doc -> DocumentKind.ARTICLE.wireValue().equals(doc.typeSlug()))
                .filter(doc -> ctx.isSysop()
                        || acl.can(ctx.session(), AclResourceType.DOCUMENT, doc.id(), AclPermission.MANAGE))
                .sorted(Comparator
                        .comparing(SysopBulletinsScreen::isPinned).reversed()
                        .thenComparing(DocumentRow::updatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(DocumentRow::id))
                .limit(9)
                .toList();
    }

    private boolean canEnter(BbsContext ctx) {
        return ctx.isSysop() || !manageableAnnouncements(ctx).isEmpty();
    }

    private boolean canCreate(BbsContext ctx) {
        return canEnter(ctx);
    }

    private static boolean isPinned(DocumentRow doc) {
        return doc.frontmatter() != null && doc.frontmatter().path("pinned").asBoolean(false);
    }
}
