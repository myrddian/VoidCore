package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.acl.AclPermission;
import io.aeyer.voidcore.acl.AclResourceType;
import io.aeyer.voidcore.acl.AclService;
import io.aeyer.voidcore.chat.ChatRepository;
import io.aeyer.voidcore.chat.ChatRoom;
import io.aeyer.voidcore.instance.InstanceFeature;
import io.aeyer.voidcore.ws.flow.Frames;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.flow.screen.Screen;
import io.aeyer.voidcore.ws.flow.screen.ScreenComponent;
import io.aeyer.voidcore.ws.flow.screen.Transition;
import io.aeyer.voidcore.ws.flow.view.ChatView;
import io.aeyer.voidcore.ws.protocol.ServerMessage.InputPrompt;
import io.aeyer.voidcore.ws.protocol.ServerMessage.Row;

import java.util.ArrayList;
import java.util.List;

@ScreenComponent
public class ChatDirectMessagesScreen implements Screen {

    private final ChatRepository repo;
    private final AclService acl;

    public ChatDirectMessagesScreen(ChatRepository repo, AclService acl) {
        this.repo = repo;
        this.acl = acl;
    }

    @Override public Phase phase() { return Phase.CHAT_DIRECTS; }
    @Override public String name() { return "chat-directs"; }

    @Override
    public List<String> topics(BbsContext ctx) {
        return ScreenFeatureGate.withTopic(List.of(ChatView.ROOMS_TOPIC));
    }

    @Override
    public Transition onEnter(BbsContext ctx) {
        if (!ScreenFeatureGate.ensureEnabled(ctx, InstanceFeature.CHAT, "chat")) {
            return Transition.None.INSTANCE;
        }
        ctx.session().setCurrentChatRoomSlug(null);
        ctx.persistCurrentScreen("{\"kind\":\"chat_directs\"}");
        List<ChatRoom> rooms = roomsFor(ctx);

        ArrayList<Row> rows = new ArrayList<>();
        rows.add(Frames.colored(0, "  == DIRECT MESSAGES ==", "bright_yellow"));
        rows.add(Frames.blank(1));
        if (rooms.isEmpty()) {
            rows.add(Frames.colored(2, "  (no direct messages yet)", "dark_grey"));
            rows.add(Frames.colored(3, "  press [N] to start one", "dark_grey"));
        } else {
            for (int i = 0; i < rooms.size(); i++) {
                ChatRoom room = rooms.get(i);
                rows.add(Frames.row(2 + i,
                        Frames.span("  [", "grey"),
                        Frames.span(String.valueOf(i + 1), "bright_yellow", true),
                        Frames.span("] ", "grey"),
                        Frames.span(room.label(), "bright_cyan", true),
                        Frames.span("  #" + room.slug(), "dark_grey")));
            }
        }
        int footerRow = rooms.isEmpty() ? 5 : 3 + rooms.size();
        rows.add(Frames.blank(footerRow));
        rows.add(Frames.row(footerRow + 1,
                Frames.span("  Pick a message thread, or [", "grey"),
                Frames.span("N", "bright_yellow", true),
                Frames.span("] new, [", "grey"),
                Frames.span("Q", "bright_yellow", true),
                Frames.span("] to return.", "grey")));
        rows.add(Frames.row(footerRow + 2,
                Frames.span("  alias: /msg <handle> [text] still works inside live chat", "dark_grey")));
        ctx.send(Frames.update("main", 52, rows));

        StringBuilder validKeys = new StringBuilder("NQ");
        for (int i = 0; i < rooms.size(); i++) validKeys.append(i + 1);
        ctx.send(new InputPrompt("keystroke", "messages:", null, validKeys.toString(), null));
        return Transition.None.INSTANCE;
    }

    @Override
    public Transition onKey(BbsContext ctx, String key) {
        if ("N".equals(key)) {
            ctx.push(Phase.CHAT_DIRECT_NEW);
            return Transition.None.INSTANCE;
        }
        if ("Q".equals(key)) {
            ctx.pop();
            return Transition.None.INSTANCE;
        }
        if (key.length() == 1 && Character.isDigit(key.charAt(0))) {
            int n = Character.digit(key.charAt(0), 10);
            List<ChatRoom> rooms = roomsFor(ctx);
            if (n >= 1 && n <= rooms.size()) {
                ctx.session().setCurrentChatRoomSlug(rooms.get(n - 1).slug());
                ctx.push(Phase.CHAT_ROOM);
            }
        }
        return Transition.None.INSTANCE;
    }

    private List<ChatRoom> roomsFor(BbsContext ctx) {
        Long userId = ctx.session().userId();
        if (userId == null) return List.of();
        return repo.listActiveDirectRoomsForUser(userId).stream()
                .filter(room -> acl.can(ctx.session(), AclResourceType.CHAT_ROOM, room.id(), AclPermission.VIEW))
                .toList();
    }
}
