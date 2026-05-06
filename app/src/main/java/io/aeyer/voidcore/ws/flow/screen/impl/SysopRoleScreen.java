package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.auth.RoleRepository;
import io.aeyer.voidcore.ws.flow.Frames;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.flow.screen.Screen;
import io.aeyer.voidcore.ws.flow.screen.ScreenComponent;
import io.aeyer.voidcore.ws.flow.screen.Transition;
import io.aeyer.voidcore.ws.protocol.ServerMessage.InputPrompt;

@ScreenComponent
public class SysopRoleScreen implements Screen {

    private final RoleRepository roles;

    public SysopRoleScreen(RoleRepository roles) {
        this.roles = roles;
    }

    @Override public Phase phase() { return Phase.SYSOP_ROLE; }
    @Override public String name() { return "sysop-role"; }

    @Override
    public Transition onEnter(BbsContext ctx) {
        if (!ctx.isSysop()) { ctx.pop(); return Transition.None.INSTANCE; }
        Long roleId = ctx.session().selectedSysopId();
        if (roleId == null) { ctx.pop(); return Transition.None.INSTANCE; }
        var role = roles.findById(roleId).orElse(null);
        if (role == null) { ctx.pop(); return Transition.None.INSTANCE; }
        ctx.session().setSelectedSysopSlug(null);
        ctx.session().setSelectedSysopResourceId(null);
        java.util.ArrayList<String> lines = new java.util.ArrayList<>();
        lines.add("");
        lines.add("  == ROLE · " + role.name() + " ==");
        lines.add("");
        if (role.description() != null && !role.description().isBlank()) {
            lines.add("  " + role.description());
            lines.add("");
        }
        lines.addAll(RolePolicySummary.detailLines(role.name()));
        lines.add("  [S] role summary");
        lines.add("  [O] one-liners");
        lines.add("  [V] voidmail");
        lines.add("  [P] polls");
        lines.add("  [C] chat rooms");
        lines.add("  [B] message boards");
        lines.add("  [D] documents");
        lines.add("  [R] releases");
        lines.add("  [A] announcements");
        lines.add("");
        lines.add("  [Q] back");
        ctx.send(Frames.update("main", 77, Frames.textRows(lines, "default")));
        ctx.send(new InputPrompt("keystroke", "role area:", null, "SOVPCBDRAQ", null));
        return Transition.None.INSTANCE;
    }

    @Override
    public Transition onKey(BbsContext ctx, String key) {
        if (!ctx.isSysop()) return Transition.None.INSTANCE;
        switch (key) {
            case "Q" -> ctx.pop();
            case "S" -> ctx.push(Phase.SYSOP_ROLE_SUMMARY);
            case "O" -> ctx.push(Phase.SYSOP_ROLE_ONELINERS);
            case "V" -> ctx.push(Phase.SYSOP_ROLE_VOIDMAIL);
            case "P" -> ctx.push(Phase.SYSOP_ROLE_POLLS);
            case "C" -> ctx.push(Phase.SYSOP_ROLE_CHAT_ROOMS);
            case "B" -> ctx.push(Phase.SYSOP_ROLE_MESSAGE_BASES);
            case "D" -> ctx.push(Phase.SYSOP_ROLE_DOCUMENTS);
            case "R" -> ctx.push(Phase.SYSOP_ROLE_RELEASES);
            case "A" -> ctx.push(Phase.SYSOP_ROLE_ANNOUNCEMENTS);
            default -> { }
        }
        return Transition.None.INSTANCE;
    }
}
