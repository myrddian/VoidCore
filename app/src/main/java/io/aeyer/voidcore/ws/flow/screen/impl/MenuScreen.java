package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.acl.AclPermission;
import io.aeyer.voidcore.acl.AclResourceType;
import io.aeyer.voidcore.acl.AclService;
import io.aeyer.voidcore.auth.UserRepository;
import io.aeyer.voidcore.auth.UserRepository.UserRow;
import io.aeyer.voidcore.instance.InstanceFeature;
import io.aeyer.voidcore.instance.InstanceFeatureService;
import io.aeyer.voidcore.ws.flow.Banner;
import io.aeyer.voidcore.ws.flow.Frames;
import io.aeyer.voidcore.ws.flow.ScreenRouter;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.flow.screen.Screen;
import io.aeyer.voidcore.ws.flow.screen.Transition;
import io.aeyer.voidcore.ws.protocol.ServerMessage;
import io.aeyer.voidcore.ws.protocol.ServerMessage.InputPrompt;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.aeyer.voidcore.ws.flow.screen.ScreenComponent;

import java.util.List;

/**
 * The main menu — single-letter dispatch hub for everything reachable
 * from the BBS root. Owns its rendering (PR-B step 7) and uses
 * {@code ctx.push(Phase.X)} for navigation per the
 * {@link io.aeyer.voidcore.ws.flow.screen.Navigator} model.
 *
 * <p>Theme cycling lives here too — [T] cycles through the four
 * available themes, persists into the user's preferences JSONB,
 * and re-paints the menu so the "(current: X)" label updates.
 *
 * <p>Letters in v1:
 * <ul>
 *   <li>{@code G} — goodbye / logout (closes session)</li>
 *   <li>{@code B} — announcements (push BULLETINS_LIST)</li>
 *   <li>{@code F} — files (push RELEASES_LIST)</li>
 *   <li>{@code U}/{@code L}/{@code W} — user list / last callers /
 *       who's online (legacy paint, INFO_VIEW phase)</li>
 *   <li>{@code O} — oneliners (push ONELINERS)</li>
 *   <li>{@code C} — multinode chat (push CHAT)</li>
 *   <li>{@code N} — voidmail (push NETMAIL_INBOX)</li>
 *   <li>{@code S} — sysop tools (push SYSOP_MENU, sysop only)</li>
 *   <li>{@code T} — cycle theme + re-paint</li>
 *   <li>{@code M} — message board / forum (push BASES_LIST)</li>
 *   <li>{@code D} — placeholder; emits a notify</li>
 * </ul>
 *
 * <p>v1.4 PR-A4: dispatch extracted (was a switch in ScreenRouter).
 * <p>v1.4 PR-B step 7: rendering moved out of ScreenRouter
 * (was {@code showMainMenu} + {@code cycleTheme} + {@code currentTheme}).
 */
@ScreenComponent
public class MenuScreen implements Screen {

    private static final Logger log = LoggerFactory.getLogger(MenuScreen.class);
    private static final String[] THEME_CYCLE = {"phosphor", "amber", "cga", "modern"};

    private final UserRepository users;
    private final ObjectMapper json;
    private final io.aeyer.voidcore.chat.ChatRepository chat;
    private final AclService acl;

    public MenuScreen(UserRepository users,
                      ObjectMapper json,
                      org.springframework.beans.factory.ObjectProvider<io.aeyer.voidcore.chat.ChatRepository> chat,
                      AclService acl) {
        this.users = users;
        this.json = json;
        this.chat = chat.getIfAvailable();
        this.acl = acl;
    }

    @Override public Phase phase() { return Phase.MENU; }
    @Override public String name() { return "main-menu"; }

    @Override
    public List<String> topics(BbsContext ctx) {
        return List.of(InstanceFeatureService.TOPIC);
    }

    @Override
    public Transition onEnter(BbsContext ctx) {
        UserRow user = ctx.user();
        if (user == null) return Transition.None.INSTANCE;
        ctx.persistCurrentScreen("{\"kind\":\"menu\"}");
        ctx.send(Frames.update("banner", 2, Banner.rows()));
        String theme = ctx.currentTheme();
        boolean files = ScreenFeatureGate.enabled(ctx, InstanceFeature.FILES);
        boolean boards = ScreenFeatureGate.enabled(ctx, InstanceFeature.MESSAGE_BOARD);
        boolean announcements = ScreenFeatureGate.enabled(ctx, InstanceFeature.ANNOUNCEMENTS);
        boolean chatEnabled = ScreenFeatureGate.enabled(ctx, InstanceFeature.CHAT);
        boolean docsEnabled = ScreenFeatureGate.enabled(ctx, InstanceFeature.INFO_DOCS);
        boolean oneliners = ScreenFeatureGate.enabled(ctx, InstanceFeature.ONELINERS);
        boolean voidmail = ScreenFeatureGate.enabled(ctx, InstanceFeature.VOIDMAIL);
        boolean polls = ScreenFeatureGate.enabled(ctx, InstanceFeature.POLLS);
        boolean doors = ScreenFeatureGate.enabled(ctx, InstanceFeature.DOORS);

        java.util.ArrayList<String> lines = new java.util.ArrayList<>(java.util.List.of(
                "",
                "  WELCOME, " + user.handle().toUpperCase() + ".",
                ""));
        if (files || boards) {
            lines.add("  " + leftEntry(files, "F", "Files")
                    + rightEntry(boards, "M", "Message board"));
        }
        if (announcements || chatEnabled) {
            lines.add("  " + leftEntry(announcements, "B", "Announcements")
                    + rightEntry(chatEnabled, "C", "Chat"));
        }
        if (docsEnabled || oneliners) {
            lines.add("  " + leftEntry(docsEnabled, "I", "Info / docs")
                    + rightEntry(oneliners, "O", "One-liners"));
        }
        lines.add("  [U] User list              " + (voidmail ? "[N] VoidMail" : ""));
        lines.add("  [L] Last callers           [W] Who's online");
        lines.add("  [A] Activity feed          [+] Watch list");
        lines.add("  [H] Achievements           " + (polls ? "[P] Polls" : ""));
        if (doors) lines.add("  [D] Doors");
        lines.add("  [T] Theme  (current: " + theme + ")");
        if (user.isSysop() || acl.hasAnyManageAccess(ctx.session())) lines.add("  [S] Operator tools");
        lines.add("");
        lines.add("  [G] Goodbye");
        // #93: chat preview — last 3 lines from the default room when
        // the current session can actually see it via ACL.
        // Surfaces
        // "what's happening right now" so users can decide if [C]hat
        // is worth dropping into. Skipped silently if the chat repo
        // is absent (DB-less profiles) or empty.
        if (chat != null) {
            var room = chat.findActiveRoomBySlug(io.aeyer.voidcore.chat.ChatRepository.DEFAULT_ROOM_SLUG).orElse(null);
            if (room != null && acl.can(ctx.session(), AclResourceType.CHAT_ROOM, room.id(), AclPermission.VIEW)) {
                java.util.List<io.aeyer.voidcore.chat.ChatMessage> recent =
                        chat.recent(io.aeyer.voidcore.chat.ChatRepository.DEFAULT_ROOM_SLUG, 3);
                if (!recent.isEmpty()) {
                    lines.add("");
                    lines.add("  -- recent chat --");
                    // ChatRepository.recent(...) returns oldest-first, so
                    // we can render top-to-bottom directly.
                    java.util.ArrayList<io.aeyer.voidcore.chat.ChatMessage> ordered =
                            new java.util.ArrayList<>(recent);
                    for (io.aeyer.voidcore.chat.ChatMessage m : ordered) {
                        String body = m.body();
                        if (body != null && body.length() > 50) {
                            body = body.substring(0, 47) + "...";
                        }
                        lines.add("  " + m.handle() + ": " + body);
                    }
                }
            }
        }
        List<ServerMessage.Row> menu = Frames.textRows(lines, "default");
        ctx.send(Frames.update("main", 2, menu));
        StringBuilder valid = new StringBuilder("ULWATH+TG");
        if (files) valid.append('F');
        if (boards) valid.append('M');
        if (announcements) valid.append('B');
        if (chatEnabled) valid.append('C');
        if (docsEnabled) valid.append('I');
        if (oneliners) valid.append('O');
        if (voidmail) valid.append('N');
        if (polls) valid.append('P');
        if (doors) valid.append('D');
        if (user.isSysop() || acl.hasAnyManageAccess(ctx.session())) valid.append('S');
        ctx.send(new InputPrompt("keystroke", "command:", null,
                valid.toString(), null));
        return Transition.None.INSTANCE;
    }

    @Override
    public Transition onKey(BbsContext ctx, String key) {
        ScreenRouter router = (ScreenRouter) ctx.router();
        switch (key) {
            case "G" -> router.onAuthLogout(ctx.session());
            case "B" -> { if (ScreenFeatureGate.enabled(ctx, InstanceFeature.ANNOUNCEMENTS)) ctx.push(Phase.BULLETINS_LIST); }
            case "F" -> { if (ScreenFeatureGate.enabled(ctx, InstanceFeature.FILES)) ctx.push(Phase.RELEASES_LIST); }
            // U/L/W all share INFO_VIEW phase but paint different
            // content; the variant rides VoidCoreSession.infoVariant
            // and InfoViewScreen branches on it in onEnter.
            case "U" -> {
                ctx.session().setInfoVariant(InfoViewScreen.VARIANT_USERS);
                ctx.push(Phase.INFO_VIEW);
            }
            case "L" -> {
                ctx.session().setInfoVariant(InfoViewScreen.VARIANT_LAST_CALLERS);
                ctx.push(Phase.INFO_VIEW);
            }
            case "W" -> {
                ctx.session().setInfoVariant(InfoViewScreen.VARIANT_WHOS_ONLINE);
                ctx.push(Phase.INFO_VIEW);
            }
            case "O" -> { if (ScreenFeatureGate.enabled(ctx, InstanceFeature.ONELINERS)) ctx.push(Phase.ONELINERS); }
            case "C" -> { if (ScreenFeatureGate.enabled(ctx, InstanceFeature.CHAT)) ctx.push(Phase.CHAT); }
            case "N" -> { if (ScreenFeatureGate.enabled(ctx, InstanceFeature.VOIDMAIL)) ctx.push(Phase.NETMAIL_INBOX); }
            case "S" -> {
                if (ctx.isSysop() || acl.hasAnyManageAccess(ctx.session())) ctx.push(Phase.SYSOP_MENU);
            }
            case "T" -> cycleTheme(ctx);
            case "M" -> { if (ScreenFeatureGate.enabled(ctx, InstanceFeature.MESSAGE_BOARD)) ctx.push(Phase.BASES_LIST); }
            case "I" -> { if (ScreenFeatureGate.enabled(ctx, InstanceFeature.INFO_DOCS)) ctx.push(Phase.DOCS_HUB); }
            case "A" -> ctx.push(Phase.ACTIVITY_FEED);
            case "+" -> ctx.push(Phase.WATCH_LIST);
            case "H" -> ctx.push(Phase.ACHIEVEMENTS);
            case "P" -> { if (ScreenFeatureGate.enabled(ctx, InstanceFeature.POLLS)) ctx.push(Phase.POLLS_LIST); }
            case "D" -> {
                if (!ScreenFeatureGate.enabled(ctx, InstanceFeature.DOORS)) return Transition.None.INSTANCE;
                ctx.push(Phase.DOORS_MENU);
            }
            default -> { /* ignored */ }
        }
        return Transition.None.INSTANCE;
    }

    private static String leftEntry(boolean enabled, String key, String label) {
        return enabled ? "[" + key + "] " + padRight(label, 22) : padRight("", 26);
    }

    private static String rightEntry(boolean enabled, String key, String label) {
        return enabled ? "[" + key + "] " + label : "";
    }

    private static String padRight(String s, int width) {
        if (s.length() >= width) return s;
        return s + " ".repeat(width - s.length());
    }

    /** Cycle through the four themes, persist into preferences, re-paint menu. */
    private void cycleTheme(BbsContext ctx) {
        UserRow user = ctx.user();
        if (user == null) return;
        long uid = user.id();
        String cur = ctx.currentTheme();
        int idx = 0;
        for (int i = 0; i < THEME_CYCLE.length; i++) if (THEME_CYCLE[i].equals(cur)) { idx = i; break; }
        String next = THEME_CYCLE[(idx + 1) % THEME_CYCLE.length];
        try {
            com.fasterxml.jackson.databind.node.ObjectNode prefs =
                    (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(users.preferences(uid));
            prefs.put("theme", next);
            users.setPreferences(uid, json.writeValueAsString(prefs));
        } catch (Exception e) {
            log.warn("setting theme {} failed for user {}: {}", next, uid, e.toString());
            return;
        }
        ctx.send(new ServerMessage.EffectSetTheme(next));
        ctx.send(Frames.notify("notifications", "theme: " + next, "info", 2000));
        // Re-paint the menu so the "(current: X)" label updates.
        onEnter(ctx);
    }
}
