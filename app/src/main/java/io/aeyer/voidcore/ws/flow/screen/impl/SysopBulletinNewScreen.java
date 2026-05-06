package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.acl.AclPermission;
import io.aeyer.voidcore.acl.AclResourceType;
import io.aeyer.voidcore.acl.AclService;
import io.aeyer.voidcore.documents.DocumentRepository;
import io.aeyer.voidcore.documents.Status;
import io.aeyer.voidcore.documents.Visibility;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aeyer.voidcore.ws.flow.Frames;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.flow.screen.ScreenAppComponent;
import io.aeyer.voidcore.ws.flow.screen.Transition;
import io.aeyer.voidcore.ws.flow.screen.form.FieldKind;
import io.aeyer.voidcore.ws.flow.screen.form.WizardFormApp;
import io.aeyer.voidcore.ws.flow.screen.form.WizardStep;
import io.aeyer.voidcore.ws.flow.view.DocumentView;

import java.util.List;
import java.util.Optional;

/**
 * Sysop · new bulletin — replaces the legacy two-screen pair
 * {@link SysopBulletinNewTitleScreen} + {@link SysopBulletinNewBodyScreen}.
 *
 * <p>Step 1: title (prefix {@code "P:"} marks the bulletin as pinned).
 * Step 2: body (multi-line editor).
 */
@ScreenAppComponent
public class SysopBulletinNewScreen extends WizardFormApp<SysopBulletinNewScreen.Draft> {

    static final class Draft {
        String title  = "";
        String body   = "";
        boolean pinned = false;
    }

    private final DocumentRepository docs;
    private final AclService acl;

    public SysopBulletinNewScreen(DocumentRepository docs, AclService acl) {
        this.docs = docs;
        this.acl = acl;
    }

    @Override public Phase phase() { return Phase.SYSOP_BULLETIN_NEW; }
    @Override public String name() { return "sysop-bulletin-new"; }

    @Override
    protected String appKey(BbsContext ctx) { return "sysop-bulletin-new"; }

    @Override
    protected Draft newState(BbsContext ctx) { return new Draft(); }

    @Override
    public Transition onEnter(BbsContext ctx) {
        if (!canCreate(ctx)) {
            ctx.send(Frames.notify("notifications",
                    "you do not have permission to create announcements", "warn", 3000));
            ctx.pop();
            return Transition.None.INSTANCE;
        }
        return super.onEnter(ctx);
    }

    @Override
    protected List<WizardStep<Draft>> steps(BbsContext ctx) {
        return List.of(
            new WizardStep<>(
                "Title", FieldKind.SINGLE_LINE,
                (d, v) -> {
                    String raw = v == null ? "" : v.trim();
                    if (raw.startsWith("P:")) {
                        d.pinned = true;
                        d.title  = raw.substring(2).trim();
                    } else {
                        d.pinned = false;
                        d.title  = raw;
                    }
                },
                v -> v == null || v.trim().isEmpty()
                    ? Optional.of("title cannot be empty") : Optional.empty()),
            new WizardStep<>(
                "Body", FieldKind.MULTI_LINE,
                (d, v) -> d.body = v == null ? "" : v,
                v -> Optional.empty())
        );
    }

    @Override
    protected void onSubmit(BbsContext ctx, Draft d) {
        Long uid = ctx.session().userId();
        if (uid == null) {
            throw new IllegalStateException("sysop announcement creation requires an authenticated user");
        }
        long id = docs.insertWithTypeSlug(
                "bulletin-" + System.currentTimeMillis(),
                d.title,
                "article",
                d.body,
                frontmatter(ctx, d.pinned),
                List.of(),
                uid,
                Visibility.PUBLIC,
                Status.PUBLISHED);
        acl.grantRoleIfPresent(AclResourceType.DOCUMENT, id, AclPermission.MANAGE, "ADMIN");
        acl.grantRoleIfPresent(AclResourceType.DOCUMENT, id, AclPermission.VIEW, "MODERATOR");
        acl.grantRoleIfPresent(AclResourceType.DOCUMENT, id, AclPermission.EDIT, "MODERATOR");
        ctx.publish(DocumentView.TOPIC);
        ctx.audit("new_bulletin", ctx.services().json().createObjectNode()
                .put("bulletin_id", id)
                .put("title", d.title)
                .put("pinned", d.pinned));
        ctx.send(Frames.notify("notifications",
                "announcement posted: " + d.title, "info", 3000));
    }

    private ObjectNode frontmatter(BbsContext ctx, boolean pinned) {
        ObjectNode fm = ctx.services().json().createObjectNode();
        if (pinned) {
            fm.put("pinned", true);
        }
        return fm;
    }

    private boolean canCreate(BbsContext ctx) {
        return ctx.isSysop() || ctx.services().documents().list().stream()
                .filter(doc -> "article".equals(doc.typeSlug()))
                .anyMatch(doc -> acl.can(ctx.session(), AclResourceType.DOCUMENT, doc.id(), AclPermission.MANAGE));
    }
}
