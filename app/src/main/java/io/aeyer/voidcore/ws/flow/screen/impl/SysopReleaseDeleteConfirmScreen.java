package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.acl.AclPermission;
import io.aeyer.voidcore.acl.AclResourceType;
import io.aeyer.voidcore.acl.AclService;
import io.aeyer.voidcore.documents.DocumentRepository;
import io.aeyer.voidcore.ws.flow.Frames;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.flow.screen.Screen;
import io.aeyer.voidcore.ws.flow.screen.ScreenComponent;
import io.aeyer.voidcore.ws.flow.screen.Transition;
import io.aeyer.voidcore.ws.flow.view.DocumentView;
import io.aeyer.voidcore.ws.protocol.ServerMessage.InputPrompt;

/**
 * Sysop · file delete confirm. Sysop must type the literal word
 * {@code "DELETE"} to proceed; anything else cancels back to the
 * edit menu. On confirm, deletes via repo + bus.notify, pops
 * twice (delete-confirm → edit-menu → releases-list) since the file
 * is gone and the edit menu would itself bounce on missing id.
 */
@ScreenComponent
public class SysopReleaseDeleteConfirmScreen implements Screen {

    private final DocumentRepository docs;
    private final AclService acl;

    public SysopReleaseDeleteConfirmScreen(DocumentRepository docs, AclService acl) {
        this.docs = docs;
        this.acl = acl;
    }

    @Override public Phase phase() { return Phase.SYSOP_RELEASE_DELETE_CONFIRM; }
    @Override public String name() { return "sysop-release-delete-confirm"; }

    @Override
    public Transition onEnter(BbsContext ctx) {
        if (!canDelete(ctx)) { ctx.pop(); return Transition.None.INSTANCE; }
        Long fid = ctx.session().selectedSysopId();
        if (fid == null) { ctx.pop(); return Transition.None.INSTANCE; }
        ctx.send(new InputPrompt("line",
                "type DELETE to confirm permanent deletion of file_id=" + fid + ":",
                16, null, null));
        return Transition.None.INSTANCE;
    }

    @Override
    public Transition onLine(BbsContext ctx, String word) {
        if (!canDelete(ctx)) return Transition.None.INSTANCE;
        Long fid = ctx.session().selectedSysopId();
        if (fid == null) { ctx.pop(); return Transition.None.INSTANCE; }
        if (!"DELETE".equals(word)) {
            ctx.send(Frames.notify("notifications",
                    "delete cancelled (must type literal DELETE)", "warn", 3000));
            ctx.pop();
            return Transition.None.INSTANCE;
        }
        docs.delete(fid);
        ctx.publish(DocumentView.TOPIC);
        ctx.audit("delete_release",
                ctx.services().json().createObjectNode().put("release_id", fid));
        ctx.send(Frames.notify("notifications",
                "file_id=" + fid + " deleted", "info", 3000));
        // File is gone — clear selection and pop twice (this
        // confirm screen and the now-stale edit-menu) back to the
        // files list. The edit-menu would self-bounce on missing
        // id anyway; popping twice is cleaner.
        ctx.session().setSelectedSysopId(null);
        ctx.pop();
        ctx.pop();
        return Transition.None.INSTANCE;
    }

    @Override
    public Transition onCancel(BbsContext ctx) { ctx.pop(); return Transition.None.INSTANCE; }

    private boolean canDelete(BbsContext ctx) {
        if (ctx.isSysop()) return true;
        Long fid = ctx.session().selectedSysopId();
        return fid != null && docs.findReleaseByIdOrLegacyFileId(fid)
                .map(doc -> acl.can(ctx.session(), AclResourceType.DOCUMENT, doc.id(), AclPermission.MANAGE))
                .orElse(false);
    }
}
