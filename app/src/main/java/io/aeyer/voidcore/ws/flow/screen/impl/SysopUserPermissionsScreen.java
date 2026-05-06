package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.acl.AclPermission;
import io.aeyer.voidcore.acl.AclResourceType;
import io.aeyer.voidcore.acl.AclService;
import io.aeyer.voidcore.auth.RoleRepository;
import io.aeyer.voidcore.auth.UserRepository;
import io.aeyer.voidcore.chat.ChatRepository;
import io.aeyer.voidcore.documents.DocumentFilter;
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
import java.util.List;

@ScreenComponent
public class SysopUserPermissionsScreen implements Screen {

    private final UserRepository users;
    private final RoleRepository roles;
    private final AclService acl;
    private final ChatRepository chat;
    private final MessageBaseRepository bases;
    private final DocumentRepository docs;
    private final PollRepository polls;

    public SysopUserPermissionsScreen(UserRepository users,
                                      RoleRepository roles,
                                      AclService acl,
                                      ChatRepository chat,
                                      MessageBaseRepository bases,
                                      DocumentRepository docs,
                                      PollRepository polls) {
        this.users = users;
        this.roles = roles;
        this.acl = acl;
        this.chat = chat;
        this.bases = bases;
        this.docs = docs;
        this.polls = polls;
    }

    @Override public Phase phase() { return Phase.SYSOP_USER_PERMISSIONS; }
    @Override public String name() { return "sysop-user-permissions"; }

    @Override
    public Transition onEnter(BbsContext ctx) {
        if (!ctx.isSysop()) { ctx.pop(); return Transition.None.INSTANCE; }
        Long userId = ctx.session().selectedSysopId();
        if (userId == null) { ctx.pop(); return Transition.None.INSTANCE; }
        var user = users.findById(userId).orElse(null);
        if (user == null) { ctx.pop(); return Transition.None.INSTANCE; }

        ArrayList<Row> rows = new ArrayList<>();
        rows.add(Frames.colored(0, "  == USER PERMISSIONS · " + user.handle().toUpperCase() + " ==", "bright_yellow"));
        rows.add(Frames.blank(1));
        String roleLine = roles.rolesForUser(userId).stream()
                .map(RoleRepository.RoleRow::name)
                .reduce((a, b) -> a + ", " + b)
                .orElse("(none)");
        rows.add(Frames.row(2,
                Frames.span("  roles : ", "grey"),
                Frames.span(roleLine, "bright_cyan")));
        rows.add(Frames.blank(3));
        int rowN = 4;
        rowN = addGlobal(rows, rowN, "one-liners", perms(userId, user.isSysop(), AclResourceType.ONELINER_WALL, OnelinersScreen.WALL_ID));
        rowN = addGlobal(rows, rowN, "voidmail", perms(userId, user.isSysop(), AclResourceType.VOIDMAIL_SYSTEM, NetmailInboxScreen.SYSTEM_ID));
        rowN = addGlobal(rows, rowN, "poll hub", perms(userId, user.isSysop(), AclResourceType.POLL, PollsListScreen.HUB_ID));
        rowN = addSection(rows, rowN, "chat rooms", chat.listAllRooms().stream()
                .map(room -> entry("#" + room.slug(), perms(userId, user.isSysop(), AclResourceType.CHAT_ROOM, room.id())))
                .filter(Entry::hasAny)
                .limit(8)
                .toList());
        rowN = addSection(rows, rowN, "message boards", bases.listAll().stream()
                .map(base -> entry(base.slug(), perms(userId, user.isSysop(), AclResourceType.MESSAGE_BASE, base.id())))
                .filter(Entry::hasAny)
                .limit(8)
                .toList());
        rowN = addSection(rows, rowN, "polls", polls.recent(8).stream()
                .map(poll -> entry(ScreenText.truncate(poll.question(), 44), perms(userId, user.isSysop(), AclResourceType.POLL, poll.id())))
                .filter(Entry::hasAny)
                .toList());
        rowN = addSection(rows, rowN, "documents", docs.findByFilter(DocumentFilter.empty(), userId, true, 0, 24).stream()
                .map(doc -> entry(ScreenText.truncate(doc.typeSlug() + " · " + doc.title(), 44),
                        perms(userId, user.isSysop(), AclResourceType.DOCUMENT, doc.id())))
                .filter(Entry::hasAny)
                .limit(8)
                .toList());
        rows.add(Frames.blank(rowN++));
        rows.add(Frames.row(rowN,
                Frames.span("  [", "grey"),
                Frames.span("Q", "bright_yellow", true),
                Frames.span("] back", "grey")));
        ctx.send(Frames.update("main", 79, rows));
        ctx.send(new InputPrompt("keystroke", "permissions:", null, "Q", null));
        return Transition.None.INSTANCE;
    }

    @Override
    public Transition onKey(BbsContext ctx, String key) {
        if ("Q".equals(key)) ctx.pop();
        return Transition.None.INSTANCE;
    }

    private int addGlobal(ArrayList<Row> rows, int rowN, String label, String perms) {
        rows.add(Frames.row(rowN++,
                Frames.span("  " + ScreenText.padRight(label, 14), "bright_cyan"),
                Frames.span(perms, perms.equals("none") ? "dark_grey" : "bright_yellow")));
        return rowN;
    }

    private int addSection(ArrayList<Row> rows, int rowN, String title, List<Entry> entries) {
        if (entries.isEmpty()) return rowN;
        rows.add(Frames.colored(rowN++, "  " + title + ":", "bright_cyan"));
        for (Entry entry : entries) {
            rows.add(Frames.row(rowN++,
                    Frames.span("    - ", "grey"),
                    Frames.span(ScreenText.padRight(entry.label(), 46), "default"),
                    Frames.span(entry.perms(), "bright_yellow")));
        }
        rows.add(Frames.blank(rowN++));
        return rowN;
    }

    private Entry entry(String label, String perms) {
        return new Entry(label, perms);
    }

    private String perms(long userId, boolean isSysop, AclResourceType type, long resourceId) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        if (acl.canUser(userId, isSysop, type, resourceId, AclPermission.VIEW)) out.add("view");
        if (acl.canUser(userId, isSysop, type, resourceId, AclPermission.POST)) out.add("post");
        if (acl.canUser(userId, isSysop, type, resourceId, AclPermission.EDIT)) out.add("edit");
        if (acl.canUser(userId, isSysop, type, resourceId, AclPermission.MANAGE)) out.add("manage");
        return out.isEmpty() ? "none" : String.join("/", out);
    }

    private record Entry(String label, String perms) {
        boolean hasAny() { return !"none".equals(perms); }
    }
}
