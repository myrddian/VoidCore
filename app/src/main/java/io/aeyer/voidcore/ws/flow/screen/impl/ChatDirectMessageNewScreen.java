package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.acl.AclService;
import io.aeyer.voidcore.auth.UserRepository;
import io.aeyer.voidcore.auth.UserRepository.UserRow;
import io.aeyer.voidcore.chat.ChatRepository;
import io.aeyer.voidcore.chat.ChatRoom;
import io.aeyer.voidcore.instance.InstanceFeature;
import io.aeyer.voidcore.ws.flow.Frames;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.flow.screen.Screen;
import io.aeyer.voidcore.ws.flow.screen.ScreenComponent;
import io.aeyer.voidcore.ws.flow.screen.Transition;
import io.aeyer.voidcore.ws.protocol.ServerMessage.InputPrompt;
import io.aeyer.voidcore.ws.protocol.ServerMessage.Row;

import java.util.ArrayList;

@ScreenComponent
public class ChatDirectMessageNewScreen implements Screen {

    private final ChatRepository repo;
    private final AclService acl;
    private final UserRepository users;

    public ChatDirectMessageNewScreen(ChatRepository repo, AclService acl, UserRepository users) {
        this.repo = repo;
        this.acl = acl;
        this.users = users;
    }

    @Override public Phase phase() { return Phase.CHAT_DIRECT_NEW; }
    @Override public String name() { return "chat-direct-new"; }

    @Override
    public Transition onEnter(BbsContext ctx) {
        if (!ScreenFeatureGate.ensureEnabled(ctx, InstanceFeature.CHAT, "chat")) {
            return Transition.None.INSTANCE;
        }
        ctx.persistCurrentScreen("{\"kind\":\"chat_direct_new\"}");

        ArrayList<Row> rows = new ArrayList<>();
        rows.add(Frames.colored(0, "  == NEW DIRECT MESSAGE ==", "bright_yellow"));
        rows.add(Frames.blank(1));
        rows.add(Frames.colored(2, "  enter a handle to open a private thread", "default"));
        rows.add(Frames.colored(3, "  [Esc] returns to messages", "dark_grey"));
        ctx.send(Frames.update("main", 53, rows));
        ctx.send(new InputPrompt("line", "handle:", 16, null, null));
        return Transition.None.INSTANCE;
    }

    @Override
    public Transition onLine(BbsContext ctx, String text) {
        Long senderUserId = ctx.session().userId();
        if (senderUserId == null) return Transition.None.INSTANCE;

        String handle = text == null ? "" : text.trim();
        if (handle.isEmpty()) {
            ctx.send(Frames.notify("notifications", "handle cannot be empty", "warn", 3000));
            return Transition.None.INSTANCE;
        }

        UserRow target = users.findByHandle(handle).orElse(null);
        if (target == null) {
            ctx.send(Frames.notify("notifications", "no such user: " + handle, "warn", 3000));
            return Transition.None.INSTANCE;
        }
        if (target.id() == senderUserId) {
            ctx.send(Frames.notify("notifications", "pick somebody else", "warn", 2500));
            return Transition.None.INSTANCE;
        }

        String senderHandle = users.findById(senderUserId).map(UserRow::handle).orElse("?");
        ChatRoom room = DirectMessageRooms.ensure(repo, acl, senderUserId, senderHandle, target);
        ctx.publish(io.aeyer.voidcore.ws.flow.view.ChatView.ROOMS_TOPIC);
        ctx.session().setCurrentChatRoomSlug(room.slug());
        ctx.persistCurrentScreen("{\"kind\":\"chat_room\",\"room\":\"" + room.slug() + "\"}");
        ctx.pop();
        ctx.push(Phase.CHAT_ROOM);
        return Transition.None.INSTANCE;
    }

    @Override
    public Transition onCancel(BbsContext ctx) {
        ctx.pop();
        return Transition.None.INSTANCE;
    }
}
