package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.auth.RoleRepository;
import io.aeyer.voidcore.ws.flow.Frames;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.flow.screen.ScreenAppComponent;
import io.aeyer.voidcore.ws.flow.screen.form.FieldKind;
import io.aeyer.voidcore.ws.flow.screen.form.WizardFormApp;
import io.aeyer.voidcore.ws.flow.screen.form.WizardStep;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

@ScreenAppComponent
public class SysopRoleNewScreen extends WizardFormApp<SysopRoleNewScreen.Draft> {

    static final class Draft {
        String name;
        String description;
    }

    private final RoleRepository roles;

    public SysopRoleNewScreen(RoleRepository roles) {
        this.roles = roles;
    }

    @Override public Phase phase() { return Phase.SYSOP_ROLE_NEW; }
    @Override public String name() { return "sysop-role-new"; }
    @Override protected String appKey(BbsContext ctx) { return "sysop-role-new"; }
    @Override protected Draft newState(BbsContext ctx) { return new Draft(); }

    @Override
    public io.aeyer.voidcore.ws.flow.screen.Transition onEnter(BbsContext ctx) {
        if (!ctx.isSysop()) { ctx.pop(); return io.aeyer.voidcore.ws.flow.screen.Transition.None.INSTANCE; }
        return super.onEnter(ctx);
    }

    @Override
    protected List<WizardStep<Draft>> steps(BbsContext ctx) {
        return List.of(
                new WizardStep<>("Name", FieldKind.SINGLE_LINE,
                        (d, v) -> d.name = normaliseName(v),
                        this::validateName),
                new WizardStep<>("Description", FieldKind.SINGLE_LINE,
                        (d, v) -> d.description = blank(v),
                        v -> Optional.empty())
        );
    }

    @Override
    protected void onSubmit(BbsContext ctx, Draft d) {
        long roleId = roles.createRole(d.name, d.description);
        ctx.audit("new_role",
                ctx.services().json().createObjectNode()
                        .put("role_id", roleId)
                        .put("name", d.name)
                        .put("description", d.description));
        ctx.send(Frames.notify("notifications",
                "role added: " + d.name, "info", 3000));
    }

    private Optional<String> validateName(String raw) {
        String name = normaliseName(raw);
        if (name.isBlank()) return Optional.of("role name cannot be empty");
        if (!name.matches("[A-Z][A-Z0-9_]{2,31}")) {
            return Optional.of("role name: A-Z, 0-9, underscore (3-32 chars)");
        }
        if (roles.findByName(name).isPresent()) {
            return Optional.of("a role with that name already exists");
        }
        return Optional.empty();
    }

    private static String normaliseName(String raw) {
        if (raw == null) return "";
        return raw.trim().toUpperCase(Locale.ROOT);
    }

    private static String blank(String raw) {
        return raw == null || raw.isBlank() ? null : raw.trim();
    }
}
