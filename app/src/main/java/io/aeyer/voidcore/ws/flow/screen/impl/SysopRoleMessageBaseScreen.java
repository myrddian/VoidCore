package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.acl.AclPermission;
import io.aeyer.voidcore.acl.AclPrincipalType;
import io.aeyer.voidcore.acl.AclRepository;
import io.aeyer.voidcore.acl.AclResourceType;
import io.aeyer.voidcore.messages.MessageBaseRepository;
import io.aeyer.voidcore.ws.flow.Frames;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.flow.screen.Screen;
import io.aeyer.voidcore.ws.flow.screen.ScreenComponent;
import io.aeyer.voidcore.ws.flow.screen.Transition;
import io.aeyer.voidcore.ws.protocol.ServerMessage.InputPrompt;

@ScreenComponent
public class SysopRoleMessageBaseScreen implements Screen {

    private final MessageBaseRepository bases;
    private final AclRepository acl;

    public SysopRoleMessageBaseScreen(MessageBaseRepository bases, AclRepository acl) {
        this.bases = bases;
        this.acl = acl;
    }

    @Override public Phase phase() { return Phase.SYSOP_ROLE_MESSAGE_BASE; }
    @Override public String name() { return "sysop-role-message-base"; }

    @Override
    public Transition onEnter(BbsContext ctx) {
        if (!ctx.isSysop()) { ctx.pop(); return Transition.None.INSTANCE; }
        Long roleId = ctx.session().selectedSysopId();
        Long baseId = ctx.session().selectedSysopResourceId();
        if (roleId == null || baseId == null) { ctx.pop(); return Transition.None.INSTANCE; }
        var base = bases.findById(baseId).orElse(null);
        if (base == null) { ctx.pop(); return Transition.None.INSTANCE; }

        boolean canView = acl.hasGrant(AclResourceType.MESSAGE_BASE, base.id(), AclPermission.VIEW, AclPrincipalType.ROLE, roleId);
        boolean canPost = acl.hasGrant(AclResourceType.MESSAGE_BASE, base.id(), AclPermission.POST, AclPrincipalType.ROLE, roleId);

        ctx.send(Frames.update("main", 78, Frames.textRows(java.util.List.of(
                "",
                "  == ROLE BOARD GRANT ==",
                "",
                "  board : " + base.name() + "  (" + base.slug() + ")",
                "",
                "  [V] view permission : " + (canView ? "ON" : "OFF"),
                "  [P] post permission : " + (canPost ? "ON" : "OFF"),
                "",
                "  [Q] back"
        ), "default")));
        ctx.send(new InputPrompt("keystroke", "grant:", null, "VPQ", null));
        return Transition.None.INSTANCE;
    }

    @Override
    public Transition onKey(BbsContext ctx, String key) {
        if (!ctx.isSysop()) return Transition.None.INSTANCE;
        if ("Q".equals(key)) { ctx.pop(); return Transition.None.INSTANCE; }
        Long roleId = ctx.session().selectedSysopId();
        Long baseId = ctx.session().selectedSysopResourceId();
        if (roleId == null || baseId == null) return Transition.None.INSTANCE;
        var base = bases.findById(baseId).orElse(null);
        if (base == null) return Transition.None.INSTANCE;

        if ("V".equals(key)) {
            boolean has = acl.hasGrant(AclResourceType.MESSAGE_BASE, base.id(), AclPermission.VIEW, AclPrincipalType.ROLE, roleId);
            if (has) {
                acl.revoke(AclResourceType.MESSAGE_BASE, base.id(), AclPermission.VIEW, AclPrincipalType.ROLE, roleId);
                acl.revoke(AclResourceType.MESSAGE_BASE, base.id(), AclPermission.POST, AclPrincipalType.ROLE, roleId);
            } else {
                acl.grant(AclResourceType.MESSAGE_BASE, base.id(), AclPermission.VIEW, AclPrincipalType.ROLE, roleId);
            }
            publishBaseRefresh(ctx, base.id(), roleId, has ? "revoked board view from role" : "granted board view to role");
        } else if ("P".equals(key)) {
            boolean hasPost = acl.hasGrant(AclResourceType.MESSAGE_BASE, base.id(), AclPermission.POST, AclPrincipalType.ROLE, roleId);
            if (hasPost) {
                acl.revoke(AclResourceType.MESSAGE_BASE, base.id(), AclPermission.POST, AclPrincipalType.ROLE, roleId);
            } else {
                acl.grant(AclResourceType.MESSAGE_BASE, base.id(), AclPermission.VIEW, AclPrincipalType.ROLE, roleId);
                acl.grant(AclResourceType.MESSAGE_BASE, base.id(), AclPermission.POST, AclPrincipalType.ROLE, roleId);
            }
            publishBaseRefresh(ctx, base.id(), roleId, hasPost ? "revoked board post from role" : "granted board post to role");
        }
        onEnter(ctx);
        return Transition.None.INSTANCE;
    }

    private void publishBaseRefresh(BbsContext ctx, long baseId, long roleId, String note) {
        ctx.publish(BasesListScreen.TOPIC);
        ctx.publish(ThreadsListScreen.topicFor(baseId));
        ctx.audit("update_role_board_acl",
                ctx.services().json().createObjectNode()
                        .put("role_id", roleId)
                        .put("base_id", baseId)
                        .put("note", note));
        ctx.send(Frames.notify("notifications", note, "info", 2500));
    }
}
