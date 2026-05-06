package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.acl.AclPermission;
import io.aeyer.voidcore.acl.AclResourceType;
import io.aeyer.voidcore.acl.AclService;
import io.aeyer.voidcore.documents.DocumentRepository;
import io.aeyer.voidcore.documents.DocumentRow;
import io.aeyer.voidcore.ws.flow.Frames;
import io.aeyer.voidcore.ws.flow.layout.Element;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.flow.screen.ScreenAppComponent;
import io.aeyer.voidcore.ws.flow.screen.form.FieldKind;
import io.aeyer.voidcore.ws.flow.screen.form.FormField;
import io.aeyer.voidcore.ws.flow.screen.form.MenuAction;
import io.aeyer.voidcore.ws.flow.screen.form.MenuFormApp;
import io.aeyer.voidcore.ws.flow.view.DocumentView;

import java.nio.charset.StandardCharsets;
import java.util.List;

@ScreenAppComponent
public class SysopBulletinEditScreen extends MenuFormApp<DocumentRow> {

    private final DocumentRepository docs;
    private final AclService acl;

    public SysopBulletinEditScreen(DocumentRepository docs, AclService acl) {
        this.docs = docs;
        this.acl = acl;
    }

    @Override public Phase phase() { return Phase.SYSOP_BULLETIN_EDIT; }
    @Override public String name() { return "sysop-bulletin-edit"; }

    @Override
    protected String appKey(BbsContext ctx) {
        Long id = ctx.session().selectedSysopId();
        return id == null ? "sysop-bulletin-edit:none" : "sysop-bulletin-edit:" + id;
    }

    @Override
    protected DocumentRow loadState(BbsContext ctx) {
        Long id = ctx.session().selectedSysopId();
        if (id == null) return null;
        DocumentRow doc = docs.findArticleByIdOrLegacyBulletinId(id).orElse(null);
        if (doc == null) return null;
        return ctx.isSysop() || acl.can(ctx.session(), AclResourceType.DOCUMENT, doc.id(), AclPermission.MANAGE)
                ? doc
                : null;
    }

    @Override
    protected String bannerLabel(BbsContext ctx, DocumentRow b) {
        return "SYSOP/ANNOUNCEMENT · " + b.title() + "  [#" + b.id() + "]";
    }

    @Override
    protected Element headerElement(BbsContext ctx, DocumentRow b) {
        return new Element.VStack(List.of(
                new Element.Header("EDIT ANNOUNCEMENT", null),
                new Element.Text("  title  : " + b.title()),
                new Element.Text("  pinned : " + (isPinned(b) ? "yes" : "no"),
                        isPinned(b) ? "bright_red" : "default")
        ), 0);
    }

    @Override
    protected List<FormField<DocumentRow>> fields(BbsContext ctx, DocumentRow b) {
        return List.of(
                new FormField<>("T", "Title", DocumentRow::title, FieldKind.SINGLE_LINE,
                        editor(this::commitTitle)),
                new FormField<>("B", "Body", DocumentRow::body, FieldKind.MULTI_LINE,
                        editor(this::commitBody)),
                new FormField<>("P", "Pinned",
                        bulletin -> isPinned(bulletin) ? "yes" : "no",
                        FieldKind.TOGGLE,
                        new FormField.FieldEditor<>() {
                            @Override
                            public String onCommit(BbsContext c, DocumentRow bulletin, String newValue) {
                                boolean pinned = "yes".equalsIgnoreCase(newValue);
                                docs.updateFrontmatterBoolean(bulletin.id(), "pinned", pinned);
                                c.publish(DocumentView.TOPIC);
                                c.audit(pinned ? "pin_bulletin" : "unpin_bulletin",
                                        c.services().json().createObjectNode()
                                                .put("bulletin_id", bulletin.id()));
                                return null;
                            }

                            @Override
                            public String nextToggleValue(DocumentRow bulletin, String currentValue) {
                                return isPinned(bulletin) ? "no" : "yes";
                            }
                        })
        );
    }

    @Override
    protected List<MenuAction<DocumentRow>> menuActions(BbsContext ctx, DocumentRow b) {
        return List.of(
                new MenuAction<>("K", "move up", null,
                        (c, bulletin) -> move(c, bulletin, true)),
                new MenuAction<>("J", "move down", null,
                        (c, bulletin) -> move(c, bulletin, false))
        );
    }

    @Override
    protected void onQuit(BbsContext ctx) {
        ctx.session().setSelectedSysopId(null);
    }

    private void move(BbsContext ctx, DocumentRow b, boolean up) {
        boolean moved = docs.moveArticle(b.id(), up ? -1 : 1);
        if (!moved) {
            ctx.send(Frames.notify("notifications",
                    up ? "already at the top of its section" : "already at the bottom of its section",
                    "warn", 2500));
            return;
        }
        ctx.publish(DocumentView.TOPIC);
        ctx.audit(up ? "move_bulletin_up" : "move_bulletin_down",
                ctx.services().json().createObjectNode().put("bulletin_id", b.id()));
        ctx.send(Frames.notify("notifications",
                up ? "announcement moved up" : "announcement moved down",
                "info", 2500));
        repaintNow(ctx);
    }

    private String commitTitle(BbsContext ctx, DocumentRow b, String title) {
        if (title == null || title.isBlank()) return "title cannot be empty";
        docs.updateTitle(b.id(), title);
        ctx.publish(DocumentView.TOPIC);
        ctx.audit("edit_bulletin_title",
                ctx.services().json().createObjectNode()
                        .put("bulletin_id", b.id())
                        .put("title", title));
        return null;
    }

    private String commitBody(BbsContext ctx, DocumentRow b, String body) {
        if (body == null) body = "";
        docs.updateBody(b.id(), body);
        ctx.publish(DocumentView.TOPIC);
        ctx.audit("edit_bulletin_body",
                ctx.services().json().createObjectNode()
                        .put("bulletin_id", b.id())
                        .put("body_bytes", body.getBytes(StandardCharsets.UTF_8).length));
        return null;
    }

    @FunctionalInterface
    private interface CommitFn {
        String apply(BbsContext ctx, DocumentRow bulletin, String value);
    }

    private static FormField.FieldEditor<DocumentRow> editor(CommitFn fn) {
        return new FormField.FieldEditor<>() {
            @Override
            public String onCommit(BbsContext c, DocumentRow bulletin, String value) {
                return fn.apply(c, bulletin, value);
            }
        };
    }

    private static boolean isPinned(DocumentRow row) {
        return row.frontmatter() != null && row.frontmatter().path("pinned").asBoolean(false);
    }
}
