package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.acl.AclPermission;
import io.aeyer.voidcore.acl.AclPrincipalType;
import io.aeyer.voidcore.acl.AclResourceType;
import io.aeyer.voidcore.acl.AclService;
import io.aeyer.voidcore.chat.ChatRepository;
import io.aeyer.voidcore.ws.flow.Frames;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.flow.screen.ScreenAppComponent;
import io.aeyer.voidcore.ws.flow.screen.form.FieldKind;
import io.aeyer.voidcore.ws.flow.screen.form.WizardFormApp;
import io.aeyer.voidcore.ws.flow.screen.form.WizardStep;
import io.aeyer.voidcore.ws.flow.view.ChatView;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

@ScreenAppComponent
public class SysopChatRoomNewScreen extends WizardFormApp<SysopChatRoomNewScreen.Draft> {

    static final class Draft {
        String slug;
        String label;
    }

    private final ChatRepository repo;
    private final AclService acl;

    public SysopChatRoomNewScreen(ChatRepository repo, AclService acl) {
        this.repo = repo;
        this.acl = acl;
    }

    @Override public Phase phase() { return Phase.SYSOP_CHAT_ROOM_NEW; }
    @Override public String name() { return "sysop-chat-room-new"; }
    @Override protected String appKey(BbsContext ctx) { return "sysop-chat-room-new"; }
    @Override protected Draft newState(BbsContext ctx) { return new Draft(); }

    @Override
    public io.aeyer.voidcore.ws.flow.screen.Transition onEnter(BbsContext ctx) {
        if (!canCreate(ctx)) {
            ctx.send(Frames.notify("notifications",
                    "you do not have permission to create chat rooms", "warn", 3000));
            ctx.pop();
            return io.aeyer.voidcore.ws.flow.screen.Transition.None.INSTANCE;
        }
        return super.onEnter(ctx);
    }

    @Override
    protected List<WizardStep<Draft>> steps(BbsContext ctx) {
        return List.of(
                new WizardStep<>("Slug", FieldKind.SINGLE_LINE,
                        (d, v) -> d.slug = normaliseSlug(v),
                        v -> validateSlug(v)),
                new WizardStep<>("Label", FieldKind.SINGLE_LINE,
                        (d, v) -> d.label = v == null ? "" : v.trim(),
                        v -> v == null || v.isBlank()
                                ? Optional.of("label cannot be empty")
                                : Optional.empty())
        );
    }

    @Override
    protected void onSubmit(BbsContext ctx, Draft d) {
        long roomId = repo.createRoom(d.slug, d.label, false);
        acl.grant(AclResourceType.CHAT_ROOM, roomId, AclPermission.VIEW, AclPrincipalType.AUTHENTICATED, null);
        acl.grant(AclResourceType.CHAT_ROOM, roomId, AclPermission.POST, AclPrincipalType.AUTHENTICATED, null);
        acl.grant(AclResourceType.CHAT_ROOM, roomId, AclPermission.MANAGE, AclPrincipalType.SYSOP, null);
        acl.grantRoleIfPresent(AclResourceType.CHAT_ROOM, roomId, AclPermission.MANAGE, "ADMIN");
        acl.grantRoleIfPresent(AclResourceType.CHAT_ROOM, roomId, AclPermission.MANAGE, "MODERATOR");
        ctx.audit("new_chat_room",
                ctx.services().json().createObjectNode()
                        .put("room_id", roomId)
                        .put("slug", d.slug)
                        .put("label", d.label));
        ctx.publish(ChatView.ROOMS_TOPIC);
        ctx.send(Frames.notify("notifications",
                "chat room added: #" + d.slug, "info", 3000));
    }

    private Optional<String> validateSlug(String raw) {
        String slug = normaliseSlug(raw);
        if (slug == null || slug.isBlank()) {
            return Optional.of("slug cannot be empty");
        }
        if (!slug.matches("[a-z0-9][a-z0-9-]{0,23}")) {
            return Optional.of("slug: lowercase letters, numbers, hyphens (max 24)");
        }
        if (repo.findRoomBySlug(slug).isPresent()) {
            return Optional.of("a room with that slug already exists");
        }
        return Optional.empty();
    }

    private static String normaliseSlug(String raw) {
        if (raw == null) return "";
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    private boolean canCreate(BbsContext ctx) {
        return ctx.isSysop() || repo.listAllRooms().stream()
                .anyMatch(room -> acl.can(ctx.session(), AclResourceType.CHAT_ROOM, room.id(), AclPermission.MANAGE));
    }
}
