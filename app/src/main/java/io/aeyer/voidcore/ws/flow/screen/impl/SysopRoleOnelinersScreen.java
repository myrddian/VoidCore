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
public class SysopRoleOnelinersScreen implements Screen {

    private final AclRepository acl;

    public SysopRoleOnelinersScreen(AclRepository acl) {
        this.acl = acl;
    }

    @Override public Phase phase() { return Phase.SYSOP_ROLE_ONELINERS; }
    @Override public String name() { return "sysop-role-oneliners"; }

    @Override
    public Transition onEnter(BbsContext ctx) {
        if (!ctx.isSysop()) { ctx.pop(); return Transition.None.INSTANCE; }
        Long roleId = ctx.session().selectedSysopId();
        if (roleId == null) { ctx.pop(); return Transition.None.INSTANCE; }

        boolean canView = acl.hasGrant(AclResourceType.ONELINER_WALL, OnelinersScreen.WALL_ID,
                AclPermission.VIEW, AclPrincipalType.ROLE, roleId);
        boolean canPost = acl.hasGrant(AclResourceType.ONELINER_WALL, OnelinersScreen.WALL_ID,
                AclPermission.POST, AclPrincipalType.ROLE, roleId);

        ctx.send(Frames.update("main", 78, Frames.textRows(java.util.List.of(
                "",
                "  == ROLE ONE-LINERS GRANT ==",
                "",
                "  wall : global one-liners",
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
        if (roleId == null) return Transition.None.INSTANCE;

        if ("V".equals(key)) {
            boolean has = acl.hasGrant(AclResourceType.ONELINER_WALL, OnelinersScreen.WALL_ID,
                    AclPermission.VIEW, AclPrincipalType.ROLE, roleId);
            if (has) {
                acl.revoke(AclResourceType.ONELINER_WALL, OnelinersScreen.WALL_ID,
                        AclPermission.VIEW, AclPrincipalType.ROLE, roleId);
                acl.revoke(AclResourceType.ONELINER_WALL, OnelinersScreen.WALL_ID,
                        AclPermission.POST, AclPrincipalType.ROLE, roleId);
            } else {
                acl.grant(AclResourceType.ONELINER_WALL, OnelinersScreen.WALL_ID,
                        AclPermission.VIEW, AclPrincipalType.ROLE, roleId);
            }
            publishWallRefresh(ctx, roleId, has ? "revoked wall view from role" : "granted wall view to role");
        } else if ("P".equals(key)) {
            boolean hasPost = acl.hasGrant(AclResourceType.ONELINER_WALL, OnelinersScreen.WALL_ID,
                    AclPermission.POST, AclPrincipalType.ROLE, roleId);
            if (hasPost) {
                acl.revoke(AclResourceType.ONELINER_WALL, OnelinersScreen.WALL_ID,
                        AclPermission.POST, AclPrincipalType.ROLE, roleId);
            } else {
                acl.grant(AclResourceType.ONELINER_WALL, OnelinersScreen.WALL_ID,
                        AclPermission.VIEW, AclPrincipalType.ROLE, roleId);
                acl.grant(AclResourceType.ONELINER_WALL, OnelinersScreen.WALL_ID,
                        AclPermission.POST, AclPrincipalType.ROLE, roleId);
            }
            publishWallRefresh(ctx, roleId, hasPost ? "revoked wall post from role" : "granted wall post to role");
        }
        onEnter(ctx);
        return Transition.None.INSTANCE;
    }

    private void publishWallRefresh(BbsContext ctx, long roleId, String note) {
        ctx.publish(OnelinersScreen.TOPIC);
        ctx.audit("update_role_oneliner_acl",
                ctx.services().json().createObjectNode()
                        .put("role_id", roleId)
                        .put("wall_id", OnelinersScreen.WALL_ID)
                        .put("note", note));
        ctx.send(Frames.notify("notifications", note, "info", 2500));
    }
}
