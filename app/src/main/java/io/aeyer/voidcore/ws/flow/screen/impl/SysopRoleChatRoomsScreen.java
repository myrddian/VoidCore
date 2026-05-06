package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.chat.ChatRepository;
import io.aeyer.voidcore.chat.ChatRoom;
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
public class SysopRoleChatRoomsScreen implements Screen {

    private final ChatRepository chat;

    public SysopRoleChatRoomsScreen(ChatRepository chat) {
        this.chat = chat;
    }

    @Override public Phase phase() { return Phase.SYSOP_ROLE_CHAT_ROOMS; }
    @Override public String name() { return "sysop-role-chat-rooms"; }

    @Override
    public Transition onEnter(BbsContext ctx) {
        if (!ctx.isSysop()) { ctx.pop(); return Transition.None.INSTANCE; }
        if (ctx.session().selectedSysopId() == null) { ctx.pop(); return Transition.None.INSTANCE; }
        ctx.session().setSelectedSysopSlug(null);

        List<ChatRoom> rooms = chat.listAllRooms();
        ArrayList<Row> rows = new ArrayList<>();
        rows.add(Frames.colored(0, "  == ROLE · CHAT ROOMS ==   " + rooms.size(), "bright_yellow"));
        rows.add(Frames.blank(1));
        int rowN = 2;
        for (int i = 0; i < rooms.size(); i++) {
            ChatRoom room = rooms.get(i);
            rows.add(Frames.row(rowN++,
                    Frames.span("  [", "grey"),
                    Frames.span(String.valueOf(i + 1), "bright_yellow", true),
                    Frames.span("] ", "grey"),
                    Frames.span(ScreenText.padRight("#" + room.slug(), 18), "bright_cyan", true),
                    Frames.span(room.active() ? "active" : "disabled",
                            room.active() ? "bright_green" : "bright_red")));
        }
        rows.add(Frames.blank(rowN++));
        rows.add(Frames.row(rowN,
                Frames.span("  Pick room number to manage grants   [", "grey"),
                Frames.span("Q", "bright_yellow", true),
                Frames.span("] back", "grey")));
        ctx.send(Frames.update("main", 77, rows));

        StringBuilder valid = new StringBuilder("Q");
        for (int i = 0; i < rooms.size(); i++) valid.append(i + 1);
        ctx.send(new InputPrompt("keystroke", "role room:", null, valid.toString(), null));
        return Transition.None.INSTANCE;
    }

    @Override
    public Transition onKey(BbsContext ctx, String key) {
        if (!ctx.isSysop()) return Transition.None.INSTANCE;
        if ("Q".equals(key)) { ctx.pop(); return Transition.None.INSTANCE; }
        if (key.length() == 1 && Character.isDigit(key.charAt(0))) {
            int idx = Character.digit(key.charAt(0), 10);
            var rooms = chat.listAllRooms();
            if (idx >= 1 && idx <= rooms.size()) {
                ctx.session().setSelectedSysopSlug(rooms.get(idx - 1).slug());
                ctx.push(Phase.SYSOP_ROLE_CHAT_ROOM);
            }
        }
        return Transition.None.INSTANCE;
    }
}
