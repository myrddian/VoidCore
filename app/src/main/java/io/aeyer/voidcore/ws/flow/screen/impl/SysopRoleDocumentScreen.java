package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.acl.AclPermission;
import io.aeyer.voidcore.acl.AclPrincipalType;
import io.aeyer.voidcore.acl.AclRepository;
import io.aeyer.voidcore.acl.AclResourceType;
import io.aeyer.voidcore.documents.DocumentRepository;
import io.aeyer.voidcore.documents.DocumentRow;
import io.aeyer.voidcore.documents.Visibility;
import io.aeyer.voidcore.ws.flow.Frames;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.flow.screen.Screen;
import io.aeyer.voidcore.ws.flow.screen.ScreenComponent;
import io.aeyer.voidcore.ws.flow.screen.Transition;
import io.aeyer.voidcore.ws.flow.view.DocumentView;
import io.aeyer.voidcore.ws.protocol.ServerMessage.InputPrompt;

@ScreenComponent
public class SysopRoleDocumentScreen implements Screen {

    private final DocumentRepository docs;
    private final AclRepository acl;

    public SysopRoleDocumentScreen(DocumentRepository docs, AclRepository acl) {
        this.docs = docs;
        this.acl = acl;
    }

    @Override public Phase phase() { return Phase.SYSOP_ROLE_DOCUMENT; }
    @Override public String name() { return "sysop-role-document"; }

    @Override
    public Transition onEnter(BbsContext ctx) {
        if (!ctx.isSysop()) { ctx.pop(); return Transition.None.INSTANCE; }
        Long roleId = ctx.session().selectedSysopId();
        Long docId = ctx.session().selectedSysopResourceId();
        if (roleId == null || docId == null) { ctx.pop(); return Transition.None.INSTANCE; }
        DocumentRow doc = docs.findById(docId).orElse(null);
        if (doc == null) { ctx.pop(); return Transition.None.INSTANCE; }

        boolean canView = acl.hasGrant(AclResourceType.DOCUMENT, doc.id(), AclPermission.VIEW, AclPrincipalType.ROLE, roleId);
        boolean canEdit = acl.hasGrant(AclResourceType.DOCUMENT, doc.id(), AclPermission.EDIT, AclPrincipalType.ROLE, roleId);
        boolean publicBaseline = doc.visibility() == Visibility.PUBLIC;

        ctx.send(Frames.update("main", 78, Frames.textRows(java.util.List.of(
                "",
                "  == ROLE DOCUMENT GRANT ==",
                "",
                "  type  : " + doc.typeSlug(),
                "  title : " + doc.title(),
                "  public baseline : " + (publicBaseline ? "yes" : "no"),
                "",
                "  [V] role view grant : " + (canView ? "ON" : "OFF"),
                "  [E] role edit grant : " + (canEdit ? "ON" : "OFF"),
                "",
                "  [Q] back"
        ), "default")));
        ctx.send(new InputPrompt("keystroke", "grant:", null, "VEQ", null));
        return Transition.None.INSTANCE;
    }

    @Override
    public Transition onKey(BbsContext ctx, String key) {
        if (!ctx.isSysop()) return Transition.None.INSTANCE;
        if ("Q".equals(key)) { ctx.pop(); return Transition.None.INSTANCE; }
        Long roleId = ctx.session().selectedSysopId();
        Long docId = ctx.session().selectedSysopResourceId();
        if (roleId == null || docId == null) return Transition.None.INSTANCE;
        DocumentRow doc = docs.findById(docId).orElse(null);
        if (doc == null) return Transition.None.INSTANCE;

        if ("V".equals(key)) {
            boolean has = acl.hasGrant(AclResourceType.DOCUMENT, doc.id(), AclPermission.VIEW, AclPrincipalType.ROLE, roleId);
            if (has) {
                acl.revoke(AclResourceType.DOCUMENT, doc.id(), AclPermission.VIEW, AclPrincipalType.ROLE, roleId);
                acl.revoke(AclResourceType.DOCUMENT, doc.id(), AclPermission.EDIT, AclPrincipalType.ROLE, roleId);
            } else {
                acl.grant(AclResourceType.DOCUMENT, doc.id(), AclPermission.VIEW, AclPrincipalType.ROLE, roleId);
            }
            publishDocumentRefresh(ctx, doc, roleId, has ? "revoked document view from role" : "granted document view to role");
        } else if ("E".equals(key)) {
            boolean hasEdit = acl.hasGrant(AclResourceType.DOCUMENT, doc.id(), AclPermission.EDIT, AclPrincipalType.ROLE, roleId);
            if (hasEdit) {
                acl.revoke(AclResourceType.DOCUMENT, doc.id(), AclPermission.EDIT, AclPrincipalType.ROLE, roleId);
            } else {
                acl.grant(AclResourceType.DOCUMENT, doc.id(), AclPermission.VIEW, AclPrincipalType.ROLE, roleId);
                acl.grant(AclResourceType.DOCUMENT, doc.id(), AclPermission.EDIT, AclPrincipalType.ROLE, roleId);
            }
            publishDocumentRefresh(ctx, doc, roleId, hasEdit ? "revoked document edit from role" : "granted document edit to role");
        }
        onEnter(ctx);
        return Transition.None.INSTANCE;
    }

    private void publishDocumentRefresh(BbsContext ctx, DocumentRow doc, long roleId, String note) {
        ctx.publish(DocumentView.TOPIC);
        ctx.audit("update_role_document_acl",
                ctx.services().json().createObjectNode()
                        .put("role_id", roleId)
                        .put("document_id", doc.id())
                        .put("type_slug", doc.typeSlug())
                        .put("note", note));
        ctx.send(Frames.notify("notifications", note, "info", 2500));
    }
}
