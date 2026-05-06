package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.acl.AclPermission;
import io.aeyer.voidcore.acl.AclPrincipalType;
import io.aeyer.voidcore.acl.AclRepository;
import io.aeyer.voidcore.acl.AclResourceType;
import io.aeyer.voidcore.ws.flow.Frames;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.flow.screen.Screen;
import io.aeyer.voidcore.ws.flow.screen.ScreenComponent;
import io.aeyer.voidcore.ws.flow.screen.Transition;
import io.aeyer.voidcore.ws.protocol.ServerMessage.InputPrompt;

@ScreenComponent
public class SysopRoleVoidmailScreen implements Screen {

    private final AclRepository acl;

    public SysopRoleVoidmailScreen(AclRepository acl) {
        this.acl = acl;
    }

    @Override public Phase phase() { return Phase.SYSOP_ROLE_VOIDMAIL; }
    @Override public String name() { return "sysop-role-voidmail"; }

    @Override
    public Transition onEnter(BbsContext ctx) {
        if (!ctx.isSysop()) { ctx.pop(); return Transition.None.INSTANCE; }
        Long roleId = ctx.session().selectedSysopId();
        if (roleId == null) { ctx.pop(); return Transition.None.INSTANCE; }

        boolean canView = acl.hasGrant(AclResourceType.VOIDMAIL_SYSTEM, NetmailInboxScreen.SYSTEM_ID,
                AclPermission.VIEW, AclPrincipalType.ROLE, roleId);
        boolean canPost = acl.hasGrant(AclResourceType.VOIDMAIL_SYSTEM, NetmailInboxScreen.SYSTEM_ID,
                AclPermission.POST, AclPrincipalType.ROLE, roleId);
        boolean canManage = acl.hasGrant(AclResourceType.VOIDMAIL_SYSTEM, NetmailInboxScreen.SYSTEM_ID,
                AclPermission.MANAGE, AclPrincipalType.ROLE, roleId);

        ctx.send(Frames.update("main", 78, Frames.textRows(java.util.List.of(
                "",
                "  == ROLE VOIDMAIL GRANT ==",
                "",
                "  subsystem : global VoidMail",
                "",
                "  [V] view permission   : " + (canView ? "ON" : "OFF"),
                "  [P] post permission   : " + (canPost ? "ON" : "OFF"),
                "  [M] manage permission : " + (canManage ? "ON" : "OFF"),
                "",
                "  [Q] back"
        ), "default")));
        ctx.send(new InputPrompt("keystroke", "grant:", null, "VPMQ", null));
        return Transition.None.INSTANCE;
    }

    @Override
    public Transition onKey(BbsContext ctx, String key) {
        if (!ctx.isSysop()) return Transition.None.INSTANCE;
        if ("Q".equals(key)) { ctx.pop(); return Transition.None.INSTANCE; }
        Long roleId = ctx.session().selectedSysopId();
        if (roleId == null) return Transition.None.INSTANCE;

        if ("V".equals(key)) {
            toggle(ctx, roleId, AclPermission.VIEW, "view");
        } else if ("P".equals(key)) {
            boolean has = acl.hasGrant(AclResourceType.VOIDMAIL_SYSTEM, NetmailInboxScreen.SYSTEM_ID,
                    AclPermission.POST, AclPrincipalType.ROLE, roleId);
            if (has) {
                acl.revoke(AclResourceType.VOIDMAIL_SYSTEM, NetmailInboxScreen.SYSTEM_ID,
                        AclPermission.POST, AclPrincipalType.ROLE, roleId);
                publish(ctx, roleId, "revoked voidmail post from role");
            } else {
                acl.grant(AclResourceType.VOIDMAIL_SYSTEM, NetmailInboxScreen.SYSTEM_ID,
                        AclPermission.VIEW, AclPrincipalType.ROLE, roleId);
                acl.grant(AclResourceType.VOIDMAIL_SYSTEM, NetmailInboxScreen.SYSTEM_ID,
                        AclPermission.POST, AclPrincipalType.ROLE, roleId);
                publish(ctx, roleId, "granted voidmail post to role");
            }
        } else if ("M".equals(key)) {
            toggle(ctx, roleId, AclPermission.MANAGE, "manage");
        }
        onEnter(ctx);
        return Transition.None.INSTANCE;
    }

    private void toggle(BbsContext ctx, long roleId, AclPermission permission, String label) {
        boolean has = acl.hasGrant(AclResourceType.VOIDMAIL_SYSTEM, NetmailInboxScreen.SYSTEM_ID,
                permission, AclPrincipalType.ROLE, roleId);
        if (has) {
            acl.revoke(AclResourceType.VOIDMAIL_SYSTEM, NetmailInboxScreen.SYSTEM_ID,
                    permission, AclPrincipalType.ROLE, roleId);
            if (permission == AclPermission.VIEW) {
                acl.revoke(AclResourceType.VOIDMAIL_SYSTEM, NetmailInboxScreen.SYSTEM_ID,
                        AclPermission.POST, AclPrincipalType.ROLE, roleId);
            }
            publish(ctx, roleId, "revoked voidmail " + label + " from role");
        } else {
            acl.grant(AclResourceType.VOIDMAIL_SYSTEM, NetmailInboxScreen.SYSTEM_ID,
                    permission, AclPrincipalType.ROLE, roleId);
            publish(ctx, roleId, "granted voidmail " + label + " to role");
        }
    }

    private void publish(BbsContext ctx, long roleId, String note) {
        ctx.publish(NetmailInboxScreen.ACL_TOPIC);
        ctx.audit("update_role_voidmail_acl",
                ctx.services().json().createObjectNode()
                        .put("role_id", roleId)
                        .put("note", note));
        ctx.send(Frames.notify("notifications", note, "info", 2500));
    }
}
