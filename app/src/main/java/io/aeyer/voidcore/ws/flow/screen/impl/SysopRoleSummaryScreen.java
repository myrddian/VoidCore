package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.acl.AclRepository;
import io.aeyer.voidcore.acl.AclResourceType;
import io.aeyer.voidcore.auth.RoleRepository;
import io.aeyer.voidcore.chat.ChatRepository;
import io.aeyer.voidcore.documents.DocumentRepository;
import io.aeyer.voidcore.messages.MessageBaseRepository;
import io.aeyer.voidcore.polls.PollRepository;
import io.aeyer.voidcore.ws.flow.Frames;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.flow.screen.Screen;
import io.aeyer.voidcore.ws.flow.screen.ScreenComponent;
import io.aeyer.voidcore.ws.flow.screen.ScreenText;
import io.aeyer.voidcore.ws.flow.screen.Transition;
import io.aeyer.voidcore.ws.protocol.ServerMessage.InputPrompt;
import io.aeyer.voidcore.ws.protocol.ServerMessage.Row;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ScreenComponent
public class SysopRoleSummaryScreen implements Screen {

    private final RoleRepository roles;
    private final AclRepository acl;
    private final ChatRepository chat;
    private final MessageBaseRepository bases;
    private final DocumentRepository docs;
    private final PollRepository polls;

    public SysopRoleSummaryScreen(RoleRepository roles,
                                  AclRepository acl,
                                  ChatRepository chat,
                                  MessageBaseRepository bases,
                                  DocumentRepository docs,
                                  PollRepository polls) {
        this.roles = roles;
        this.acl = acl;
        this.chat = chat;
        this.bases = bases;
        this.docs = docs;
        this.polls = polls;
    }

    @Override public Phase phase() { return Phase.SYSOP_ROLE_SUMMARY; }
    @Override public String name() { return "sysop-role-summary"; }

    @Override
    public Transition onEnter(BbsContext ctx) {
        if (!ctx.isSysop()) { ctx.pop(); return Transition.None.INSTANCE; }
        Long roleId = ctx.session().selectedSysopId();
        if (roleId == null) { ctx.pop(); return Transition.None.INSTANCE; }
        var role = roles.findById(roleId).orElse(null);
        if (role == null) { ctx.pop(); return Transition.None.INSTANCE; }

        Map<AclResourceType, Map<Long, List<String>>> grouped = new EnumMap<>(AclResourceType.class);
        for (var grant : acl.listRoleGrants(roleId)) {
            grouped.computeIfAbsent(grant.resourceType(), __ -> new LinkedHashMap<>())
                    .computeIfAbsent(grant.resourceId(), __ -> new ArrayList<>())
                    .add(grant.permission().wireValue());
        }

        ArrayList<Row> rows = new ArrayList<>();
        rows.add(Frames.colored(0, "  == ROLE SUMMARY · " + role.name() + " ==", "bright_yellow"));
        rows.add(Frames.blank(1));
        int rowN = 2;
        rowN = appendSection(rows, rowN, "chat rooms",
                grouped.getOrDefault(AclResourceType.CHAT_ROOM, Map.of()), this::chatLabel);
        rowN = appendSection(rows, rowN, "one-liners",
                grouped.getOrDefault(AclResourceType.ONELINER_WALL, Map.of()), this::onelinerLabel);
        rowN = appendSection(rows, rowN, "voidmail",
                grouped.getOrDefault(AclResourceType.VOIDMAIL_SYSTEM, Map.of()), this::voidmailLabel);
        rowN = appendSection(rows, rowN, "polls",
                grouped.getOrDefault(AclResourceType.POLL, Map.of()), this::pollLabel);
        rowN = appendSection(rows, rowN, "message boards",
                grouped.getOrDefault(AclResourceType.MESSAGE_BASE, Map.of()), this::baseLabel);
        rowN = appendSection(rows, rowN, "documents",
                grouped.getOrDefault(AclResourceType.DOCUMENT, Map.of()), this::documentLabel);
        if (rowN == 2) {
            rows.add(Frames.colored(rowN++, "  (no explicit role grants yet)", "dark_grey"));
        }
        rows.add(Frames.blank(rowN++));
        rows.add(Frames.row(rowN,
                Frames.span("  [", "grey"),
                Frames.span("Q", "bright_yellow", true),
                Frames.span("] back", "grey")));
        ctx.send(Frames.update("main", 79, rows));
        ctx.send(new InputPrompt("keystroke", "role summary:", null, "Q", null));
        return Transition.None.INSTANCE;
    }

    @Override
    public Transition onKey(BbsContext ctx, String key) {
        if ("Q".equals(key)) ctx.pop();
        return Transition.None.INSTANCE;
    }

    private int appendSection(ArrayList<Row> rows,
                              int rowN,
                              String title,
                              Map<Long, List<String>> resources,
                              java.util.function.Function<Long, String> labeler) {
        if (resources.isEmpty()) return rowN;
        rows.add(Frames.colored(rowN++, "  " + title + ":", "bright_cyan"));
        for (var entry : resources.entrySet()) {
            String perms = String.join("/", sortPermissions(entry.getValue()));
            String label = ScreenText.truncate(labeler.apply(entry.getKey()), 52);
            rows.add(Frames.row(rowN++,
                    Frames.span("    - ", "grey"),
                    Frames.span(ScreenText.padRight(label, 54), "default"),
                    Frames.span(perms, "bright_yellow", true)));
        }
        rows.add(Frames.blank(rowN++));
        return rowN;
    }

    private List<String> sortPermissions(List<String> permissions) {
        return permissions.stream()
                .sorted(java.util.Comparator.comparingInt(this::permissionRank))
                .toList();
    }

    private int permissionRank(String permission) {
        return switch (permission) {
            case "view" -> 0;
            case "post" -> 1;
            case "edit" -> 2;
            case "manage" -> 3;
            default -> 99;
        };
    }

    private String chatLabel(long roomId) {
        return chat.findRoomById(roomId)
                .map(r -> "#" + r.slug() + " · " + r.label())
                .orElse("room #" + roomId);
    }

    private String baseLabel(long baseId) {
        return bases.findById(baseId)
                .map(b -> b.slug() + " · " + b.name())
                .orElse("board #" + baseId);
    }

    private String onelinerLabel(long resourceId) {
        return resourceId == OnelinersScreen.WALL_ID ? "global wall" : "wall #" + resourceId;
    }

    private String voidmailLabel(long resourceId) {
        return resourceId == NetmailInboxScreen.SYSTEM_ID ? "global subsystem" : "voidmail #" + resourceId;
    }

    private String pollLabel(long resourceId) {
        if (resourceId == PollsListScreen.HUB_ID) return "poll hub";
        return polls.findById(resourceId)
                .map(p -> "poll · " + p.question())
                .orElse("poll #" + resourceId);
    }

    private String documentLabel(long docId) {
        return docs.findById(docId)
                .map(d -> d.typeSlug() + " · " + d.title())
                .orElse("document #" + docId);
    }
}
