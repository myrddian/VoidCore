package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.acl.AclPermission;
import io.aeyer.voidcore.acl.AclPrincipalType;
import io.aeyer.voidcore.acl.AclRepository;
import io.aeyer.voidcore.acl.AclResourceType;
import io.aeyer.voidcore.polls.PollRepository;
import io.aeyer.voidcore.ws.flow.Frames;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.flow.screen.Screen;
import io.aeyer.voidcore.ws.flow.screen.ScreenComponent;
import io.aeyer.voidcore.ws.flow.screen.Transition;
import io.aeyer.voidcore.ws.protocol.ServerMessage.InputPrompt;

@ScreenComponent
public class SysopRolePollScreen implements Screen {

    private final PollRepository polls;
    private final AclRepository acl;

    public SysopRolePollScreen(PollRepository polls, AclRepository acl) {
        this.polls = polls;
        this.acl = acl;
    }

    @Override public Phase phase() { return Phase.SYSOP_ROLE_POLL; }
    @Override public String name() { return "sysop-role-poll"; }

    @Override
    public Transition onEnter(BbsContext ctx) {
        if (!ctx.isSysop()) { ctx.pop(); return Transition.None.INSTANCE; }
        Long roleId = ctx.session().selectedSysopId();
        Long pollId = ctx.session().selectedSysopResourceId();
        if (roleId == null || pollId == null) { ctx.pop(); return Transition.None.INSTANCE; }
        String label = pollId == PollsListScreen.HUB_ID
                ? "poll hub"
                : polls.findById(pollId).map(PollRepository.Poll::question).orElse(null);
        if (label == null) { ctx.pop(); return Transition.None.INSTANCE; }

        boolean canView = acl.hasGrant(AclResourceType.POLL, pollId, AclPermission.VIEW, AclPrincipalType.ROLE, roleId);
        boolean canPost = acl.hasGrant(AclResourceType.POLL, pollId, AclPermission.POST, AclPrincipalType.ROLE, roleId);
        boolean canManage = acl.hasGrant(AclResourceType.POLL, pollId, AclPermission.MANAGE, AclPrincipalType.ROLE, roleId);

        ctx.send(Frames.update("main", 78, Frames.textRows(java.util.List.of(
                "",
                "  == ROLE POLL GRANT ==",
                "",
                "  target : " + label,
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
        Long pollId = ctx.session().selectedSysopResourceId();
        if (roleId == null || pollId == null) return Transition.None.INSTANCE;

        if ("V".equals(key)) {
            boolean has = acl.hasGrant(AclResourceType.POLL, pollId, AclPermission.VIEW, AclPrincipalType.ROLE, roleId);
            if (has) {
                acl.revoke(AclResourceType.POLL, pollId, AclPermission.VIEW, AclPrincipalType.ROLE, roleId);
                acl.revoke(AclResourceType.POLL, pollId, AclPermission.POST, AclPrincipalType.ROLE, roleId);
            } else {
                acl.grant(AclResourceType.POLL, pollId, AclPermission.VIEW, AclPrincipalType.ROLE, roleId);
            }
            publish(ctx, roleId, pollId, has ? "revoked poll view from role" : "granted poll view to role");
        } else if ("P".equals(key)) {
            boolean has = acl.hasGrant(AclResourceType.POLL, pollId, AclPermission.POST, AclPrincipalType.ROLE, roleId);
            if (has) {
                acl.revoke(AclResourceType.POLL, pollId, AclPermission.POST, AclPrincipalType.ROLE, roleId);
            } else {
                acl.grant(AclResourceType.POLL, pollId, AclPermission.VIEW, AclPrincipalType.ROLE, roleId);
                acl.grant(AclResourceType.POLL, pollId, AclPermission.POST, AclPrincipalType.ROLE, roleId);
            }
            publish(ctx, roleId, pollId, has ? "revoked poll post from role" : "granted poll post to role");
        } else if ("M".equals(key)) {
            boolean has = acl.hasGrant(AclResourceType.POLL, pollId, AclPermission.MANAGE, AclPrincipalType.ROLE, roleId);
            if (has) {
                acl.revoke(AclResourceType.POLL, pollId, AclPermission.MANAGE, AclPrincipalType.ROLE, roleId);
            } else {
                acl.grant(AclResourceType.POLL, pollId, AclPermission.MANAGE, AclPrincipalType.ROLE, roleId);
            }
            publish(ctx, roleId, pollId, has ? "revoked poll manage from role" : "granted poll manage to role");
        }
        onEnter(ctx);
        return Transition.None.INSTANCE;
    }

    private void publish(BbsContext ctx, long roleId, long pollId, String note) {
        ctx.publish(PollsListScreen.TOPIC);
        ctx.audit("update_role_poll_acl",
                ctx.services().json().createObjectNode()
                        .put("role_id", roleId)
                        .put("poll_id", pollId)
                        .put("note", note));
        ctx.send(Frames.notify("notifications", note, "info", 2500));
    }
}
