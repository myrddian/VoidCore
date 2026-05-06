package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.acl.AclPermission;
import io.aeyer.voidcore.acl.AclResourceType;
import io.aeyer.voidcore.acl.AclService;
import io.aeyer.voidcore.auth.RateLimitDecision;
import io.aeyer.voidcore.auth.RateLimiter;
import io.aeyer.voidcore.auth.UserRepository;
import io.aeyer.voidcore.auth.UserRepository.UserRow;
import io.aeyer.voidcore.chat.ChatMessage;
import io.aeyer.voidcore.chat.ChatRoom;
import io.aeyer.voidcore.chat.ChatRepository;
import io.aeyer.voidcore.instance.InstanceFeature;
import io.aeyer.voidcore.ws.VoidCoreSession;
import io.aeyer.voidcore.ws.flow.Frames;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.flow.screen.Screen;
import io.aeyer.voidcore.ws.flow.screen.ScreenComponent;
import io.aeyer.voidcore.ws.flow.screen.Transition;
import io.aeyer.voidcore.ws.flow.view.ChatView;
import io.aeyer.voidcore.ws.protocol.ServerMessage.InputPrompt;
import io.aeyer.voidcore.ws.protocol.ServerMessage.Row;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * One live chat room — line-mode submit posts to the current room;
 * {@code /me action} prefix becomes a {@code KIND_ACTION} message;
 * Esc returns to the room list.
 *
 * <p>v1.4 PR-A: extracted as a Screen.
 *
 * <p>v1.4 PR-B step 18: rendering, validation, rate-limit, and the
 * cross-session live-update all move here. Reads via {@link ChatView}
 * (cache + bus invalidation per ADR-029); writes go through
 * {@link ChatRepository} directly with a room-scoped bus topic
 * driving repaint of every viewer (writer + peers). The legacy
 * {@code broadcastChatRoom} loop in ScreenRouter is gone.
 *
 * <p>{@link #onEvent} repaints the chat frame only — never re-emits
 * the {@code InputPrompt}, or a peer mid-typing would have their
 * input cleared. The writer's fresh prompt is sent explicitly by
 * {@link #onLine}.
 */
@ScreenComponent
public class ChatRoomScreen implements Screen {

    private static final Logger log = LoggerFactory.getLogger(ChatRoomScreen.class);
    private static final int CHAT_MAX_LEN = 256;
    private final ChatRepository repo;
    private final AclService acl;
    private final RateLimiter rateLimiter;
    private final UserRepository users;

    public ChatRoomScreen(ChatRepository repo, AclService acl, RateLimiter rateLimiter, UserRepository users) {
        this.repo = repo;
        this.acl = acl;
        this.rateLimiter = rateLimiter;
        this.users = users;
    }

    @Override public Phase phase() { return Phase.CHAT_ROOM; }
    @Override public String name() { return "chat-room"; }

    @Override
    public List<String> topics(BbsContext ctx) {
        String roomSlug = ctx.session().currentChatRoomSlug();
        return roomSlug == null
                ? ScreenFeatureGate.withTopic(List.of())
                : ScreenFeatureGate.withTopic(List.of(ChatView.topicFor(roomSlug)));
    }

    @Override
    public Transition onEvent(BbsContext ctx, String topic) {
        if (!ScreenFeatureGate.enabled(ctx, InstanceFeature.CHAT)) {
            ctx.session().setCurrentChatRoomSlug(null);
            ctx.send(Frames.notify("notifications",
                    "chat is disabled on this board", "warn", 3000));
            ctx.pop();
            return Transition.None.INSTANCE;
        }
        if (currentRoom(ctx).isEmpty()) {
            ctx.session().setCurrentChatRoomSlug(null);
            ctx.send(Frames.notify("notifications",
                    "that chat room is no longer available", "warn", 3000));
            ctx.pop();
            return Transition.None.INSTANCE;
        }
        // Paint-only: peers mid-typing keep their cursor.
        renderFrame(ctx);
        return Transition.None.INSTANCE;
    }

    @Override
    public Transition onEnter(BbsContext ctx) {
        if (!ScreenFeatureGate.ensureEnabled(ctx, InstanceFeature.CHAT, "chat")) {
            return Transition.None.INSTANCE;
        }
        ChatRoom room = currentRoom(ctx).orElse(null);
        if (room == null) {
            ctx.session().setCurrentChatRoomSlug(null);
            ctx.pop();
            return Transition.None.INSTANCE;
        }
        ctx.persistCurrentScreen("{\"kind\":\"chat_room\",\"room\":\"" + room.slug() + "\"}");
        renderFrame(ctx);
        emitPrompt(ctx);
        return Transition.None.INSTANCE;
    }

    /** Paint the room transcript to this session, no prompt. */
    private void renderFrame(BbsContext ctx) {
        ChatRoom room = currentRoom(ctx).orElse(null);
        if (room == null) return;
        List<ChatMessage> history = ctx.services().chat().recent(room.slug());
        ArrayList<Row> rows = new ArrayList<>();
        rows.add(Frames.colored(0,
                room.directMessageRoom()
                        ? "  == DIRECT MESSAGE ==   " + room.label()
                        : "  == MULTINODE CHAT ==   #" + room.slug(),
                "bright_yellow"));
        rows.add(Frames.blank(1));
        int rowN = 2;
        if (history.isEmpty()) {
            rows.add(Frames.colored(rowN++,
                    "  (the room is quiet — say something)", "dark_grey"));
        }
        for (ChatMessage m : history) {
            rows.add(renderChatRow(rowN++, m));
        }
        try {
            ctx.session().send(Frames.update("main", 50, rows));
        } catch (IOException e) {
            log.debug("chat frame send failed for session={}: {}",
                    ctx.session().id(), e.toString());
        }
    }

    private static Row renderChatRow(int rowN, ChatMessage m) {
        String when = m.postedAt() == null ? ""
                : m.postedAt().toString().substring(11, 16);
        return switch (m.kind()) {
            case ChatRepository.KIND_ACTION -> Frames.row(rowN,
                    Frames.span("  ", null),
                    Frames.span(when, "dark_grey"),
                    Frames.span("  * ", "bright_yellow"),
                    Frames.span(m.handle() + " ", "bright_cyan", true),
                    Frames.span(m.body(), "default"));
            case ChatRepository.KIND_SYSTEM -> Frames.row(rowN,
                    Frames.span("  ", null),
                    Frames.span(when, "dark_grey"),
                    Frames.span("  *** SYSTEM: ", "bright_red"),
                    Frames.span(m.body(), "bright_red"));
            default -> Frames.row(rowN,
                    Frames.span("  ", null),
                    Frames.span(when, "dark_grey"),
                    Frames.span("  <", "grey"),
                    Frames.span(m.handle(), "bright_cyan", true),
                    Frames.span("> ", "grey"),
                    Frames.span(m.body(), "default"));
        };
    }

    private void emitPrompt(BbsContext ctx) {
        ChatRoom room = currentRoom(ctx).orElse(null);
        String roomSlug = room == null ? ctx.session().currentChatRoomSlug() : room.slug();
        ctx.send(new InputPrompt("line",
                (room != null && room.directMessageRoom()
                        ? "dm:" + room.label()
                        : "#" + (roomSlug == null ? "chat" : roomSlug))
                        + " (/me, /msg handle [text], /reply [text], [Esc] to leave):",
                CHAT_MAX_LEN, null, null));
    }

    @Override
    public Transition onLine(BbsContext ctx, String body) {
        body = body == null ? "" : body.trim();
        if (body.isEmpty()) {
            // Empty enter: refresh self, no publish (nothing changed
            // for peers).
            renderFrame(ctx);
            emitPrompt(ctx);
            return Transition.None.INSTANCE;
        }
        if (body.length() > CHAT_MAX_LEN) {
            ctx.send(Frames.notify("notifications",
                    "message too long (max " + CHAT_MAX_LEN + " chars)",
                    "alert", 3000));
            return Transition.None.INSTANCE;
        }
        VoidCoreSession session = ctx.session();
        Long uid = session.userId();
        if (uid == null) return Transition.None.INSTANCE;
        ChatRoom room = currentRoom(ctx).orElse(null);
        if (room == null) {
            ctx.session().setCurrentChatRoomSlug(null);
            ctx.send(Frames.notify("notifications",
                    "that chat room is no longer available", "warn", 3000));
            ctx.pop();
            return Transition.None.INSTANCE;
        }
        if (!acl.can(session, AclResourceType.CHAT_ROOM, room.id(), AclPermission.POST)) {
            ctx.send(Frames.notify("notifications",
                    "you no longer have permission to post in that room", "warn", 3000));
            return Transition.None.INSTANCE;
        }
        var rl = rateLimiter.checkAndRecordPost(uid, RateLimiter.PostKind.CHAT);
        if (rl instanceof RateLimitDecision.Denied d) {
            long secs = Math.max(1, d.retryAfterMs() / 1000);
            ctx.send(Frames.notify("notifications",
                    "slow down — try again in " + secs + "s", "warn", 3000));
            return Transition.None.INSTANCE;
        }

        String kind = ChatRepository.KIND_MSG;
        String text = body;
        if (text.startsWith("/me ")) {
            kind = ChatRepository.KIND_ACTION;
            text = text.substring("/me ".length()).trim();
        }
        if (text.startsWith("/msg")) {
            return handleDirectMessage(ctx, room, uid, text);
        }
        if (text.startsWith("/reply")) {
            return handleReply(ctx, room, uid, text);
        }
        if (text.isEmpty()) return Transition.None.INSTANCE;

        repo.insert(room.id(), uid, text, kind);
        // Bus delivery handles writer + peers: every subscriber to
        // this room topic gets onEvent → renderFrame. Writer needs a fresh
        // prompt because their previous line-mode input was
        // consumed; peers don't.
        ctx.publish(ChatView.topicFor(room.slug()));
        emitPrompt(ctx);
        // @-mention popups: targeted notification per ADR-027
        // (separate semantic from the topic invalidation bus).
        ctx.services().mentions().notify(session, text, "chat #" + room.slug(), Phase.CHAT_ROOM);
        return Transition.None.INSTANCE;
    }

    @Override
    public Transition onCancel(BbsContext ctx) {
        ctx.session().setCurrentChatRoomSlug(null);
        ctx.pop();
        return Transition.None.INSTANCE;
    }

    private Transition handleDirectMessage(BbsContext ctx, ChatRoom room, long senderUserId, String command) {
        String rest = command.length() <= 4 ? "" : command.substring(4).trim();
        if (rest.isEmpty()) {
            ctx.send(Frames.notify("notifications",
                    "usage: /msg <handle> [text]", "warn", 3000));
            emitPrompt(ctx);
            return Transition.None.INSTANCE;
        }

        int split = rest.indexOf(' ');
        String targetHandle = split < 0 ? rest : rest.substring(0, split).trim();
        String message = split < 0 ? "" : rest.substring(split + 1).trim();
        UserRow target = users.findByHandle(targetHandle).orElse(null);
        if (target == null) {
            ctx.send(Frames.notify("notifications",
                    "no such user: " + targetHandle, "warn", 3000));
            emitPrompt(ctx);
            return Transition.None.INSTANCE;
        }
        if (target.id() == senderUserId) {
            ctx.send(Frames.notify("notifications",
                    "you already know what you think", "warn", 2500));
            emitPrompt(ctx);
            return Transition.None.INSTANCE;
        }

        ChatRoom dmRoom = DirectMessageRooms.ensure(repo, acl, senderUserId,
                users.findById(senderUserId).map(UserRow::handle).orElse("?"),
                target);
        ctx.publish(ChatView.ROOMS_TOPIC);
        switchToRoom(ctx, dmRoom);
        if (!message.isEmpty()) {
            repo.insert(dmRoom.id(), senderUserId, message, ChatRepository.KIND_MSG);
            ctx.publish(ChatView.topicFor(dmRoom.slug()));
            ctx.publish(ChatView.ROOMS_TOPIC);
            notifyDirectPeers(ctx, senderUserId, dmRoom);
        } else {
            ctx.send(Frames.notify("notifications",
                    "opened DM with " + target.handle(), "info", 2500));
        }
        renderFrame(ctx);
        emitPrompt(ctx);
        return Transition.None.INSTANCE;
    }

    private Transition handleReply(BbsContext ctx, ChatRoom currentRoom, long senderUserId, String command) {
        ChatRoom room = currentRoom != null && currentRoom.directMessageRoom()
                ? currentRoom
                : repo.findLatestDirectRoomForUser(senderUserId).orElse(null);
        if (room == null) {
            ctx.send(Frames.notify("notifications",
                    "no direct message thread yet — use /msg <handle>", "warn", 3000));
            emitPrompt(ctx);
            return Transition.None.INSTANCE;
        }

        String message = command.length() <= 6 ? "" : command.substring(6).trim();
        switchToRoom(ctx, room);
        if (!message.isEmpty()) {
            repo.insert(room.id(), senderUserId, message, ChatRepository.KIND_MSG);
            ctx.publish(ChatView.topicFor(room.slug()));
            ctx.publish(ChatView.ROOMS_TOPIC);
            notifyDirectPeers(ctx, senderUserId, room);
        }
        renderFrame(ctx);
        emitPrompt(ctx);
        return Transition.None.INSTANCE;
    }

    private void notifyDirectPeers(BbsContext ctx, long senderUserId, ChatRoom room) {
        String senderHandle = users.findById(senderUserId).map(UserRow::handle).orElse("?");
        for (Long participantId : repo.listRoomParticipantIds(room.id())) {
            if (participantId == null || participantId == senderUserId) continue;
            ctx.services().mentions().notifyUser(
                    participantId, null,
                    "new DM from " + senderHandle + " — enter chat and press [M]essages",
                    5000);
        }
    }

    private void switchToRoom(BbsContext ctx, ChatRoom room) {
        ctx.session().setCurrentChatRoomSlug(room.slug());
        ctx.persistCurrentScreen("{\"kind\":\"chat_room\",\"room\":\"" + room.slug() + "\"}");
    }

    private Optional<ChatRoom> currentRoom(BbsContext ctx) {
        return repo.findActiveRoomBySlug(ctx.session().currentChatRoomSlug())
                .filter(room -> acl.can(ctx.session(), AclResourceType.CHAT_ROOM, room.id(), AclPermission.VIEW));
    }
}
