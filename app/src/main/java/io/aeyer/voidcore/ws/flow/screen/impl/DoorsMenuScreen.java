package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.doors.DoorRuntimeService;
import io.aeyer.voidcore.doors.DoorSummary;
import io.aeyer.voidcore.instance.InstanceFeature;
import io.aeyer.voidcore.instance.InstanceFeatureService;
import io.aeyer.voidcore.ws.flow.Frames;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.flow.screen.Screen;
import io.aeyer.voidcore.ws.flow.screen.ScreenComponent;
import io.aeyer.voidcore.ws.flow.screen.Transition;
import io.aeyer.voidcore.ws.protocol.ServerMessage.InputPrompt;
import io.aeyer.voidcore.ws.protocol.ServerMessage.Row;

import java.util.ArrayList;
import java.util.List;

@ScreenComponent
public class DoorsMenuScreen implements Screen {

    private final DoorRuntimeService doors;

    public DoorsMenuScreen(DoorRuntimeService doors) {
        this.doors = doors;
    }

    @Override public Phase phase() { return Phase.DOORS_MENU; }
    @Override public String name() { return "doors-menu"; }

    @Override
    public List<String> topics(BbsContext ctx) {
        return List.of(InstanceFeatureService.TOPIC, DoorRuntimeService.CATALOG_TOPIC);
    }

    @Override
    public Transition onEnter(BbsContext ctx) {
        if (!ScreenFeatureGate.ensureEnabled(ctx, InstanceFeature.DOORS, "Doors")) {
            return Transition.None.INSTANCE;
        }
        ctx.session().setCurrentDoorId(null);
        ctx.persistCurrentScreen("{\"kind\":\"doors_menu\"}");

        List<DoorSummary> list = doors.listConnectedDoors();
        ArrayList<String> lines = new ArrayList<>(List.of(
                "",
                "  == DOORS ==",
                ""));
        if (list.isEmpty()) {
            lines.add("  No network doors are connected.");
            lines.add("  Start a sidecar on /ws/door and come back.");
        } else {
            for (int i = 0; i < Math.min(9, list.size()); i++) {
                DoorSummary door = list.get(i);
                lines.add("  [" + (i + 1) + "] " + door.name() + "  -  " + trim(door.description(), 44));
                lines.add("      id: " + door.doorId() + "  sessions: " + door.attachedSessions());
            }
        }
        lines.add("");
        lines.add("  [Q] Back");
        List<Row> rows = Frames.textRows(lines, "default");
        ctx.send(Frames.update("main", 2, rows));
        String valid = list.isEmpty() ? "Q" : "123456789".substring(0, Math.min(9, list.size())) + "Q";
        ctx.send(new InputPrompt("keystroke", "door:", null, valid, null));
        return Transition.None.INSTANCE;
    }

    @Override
    public Transition onKey(BbsContext ctx, String key) {
        if ("Q".equals(key)) {
            ctx.pop();
            return Transition.None.INSTANCE;
        }
        if (key.length() == 1 && Character.isDigit(key.charAt(0))) {
            int idx = key.charAt(0) - '1';
            List<DoorSummary> list = doors.listConnectedDoors();
            if (idx >= 0 && idx < list.size()) {
                ctx.session().setCurrentDoorId(list.get(idx).doorId());
                ctx.push(Phase.DOOR_SESSION);
            }
        }
        return Transition.None.INSTANCE;
    }

    private static String trim(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max - 3) + "...";
    }
}
