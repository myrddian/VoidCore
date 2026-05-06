package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.doors.DoorRuntimeService;
import io.aeyer.voidcore.doors.DoorSessionState;
import io.aeyer.voidcore.instance.InstanceFeature;
import io.aeyer.voidcore.instance.InstanceFeatureService;
import io.aeyer.voidcore.ws.VoidCoreSession;
import io.aeyer.voidcore.ws.flow.Banner;
import io.aeyer.voidcore.ws.flow.Frames;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.flow.screen.Screen;
import io.aeyer.voidcore.ws.flow.screen.ScreenComponent;
import io.aeyer.voidcore.ws.flow.screen.Transition;
import io.aeyer.voidcore.ws.protocol.ServerMessage.InputPrompt;

import java.util.List;

@ScreenComponent
public class DoorSessionScreen implements Screen {

    private final DoorRuntimeService doors;

    public DoorSessionScreen(DoorRuntimeService doors) {
        this.doors = doors;
    }

    @Override public Phase phase() { return Phase.DOOR_SESSION; }
    @Override public String name() { return "door-session"; }

    @Override
    public List<String> topics(BbsContext ctx) {
        return List.of(InstanceFeatureService.TOPIC, doors.topicFor(ctx.session().id()));
    }

    @Override
    public Transition onEnter(BbsContext ctx) {
        if (!ScreenFeatureGate.ensureEnabled(ctx, InstanceFeature.DOORS, "Doors")) {
            return Transition.None.INSTANCE;
        }
        String doorId = ctx.session().currentDoorId();
        if (doorId == null || doorId.isBlank()) {
            ctx.pop();
            return Transition.None.INSTANCE;
        }
        doors.ensureAttached(ctx.session(), doorId, ctx.currentTheme());
        DoorSessionState state = doors.stateFor(ctx.session());
        if (state == null) {
            ctx.send(Frames.notify("notifications", "door unavailable", "warn", 2000));
            ctx.session().setCurrentDoorId(null);
            ctx.pop();
            return Transition.None.INSTANCE;
        }
        ctx.persistCurrentScreen("{\"kind\":\"door_session\",\"door_id\":\"" + state.doorId() + "\"}");
        paint(ctx, state);
        return Transition.None.INSTANCE;
    }

    @Override
    public Transition onEvent(BbsContext ctx, String topic) {
        if (InstanceFeatureService.TOPIC.equals(topic)) return onEnter(ctx);
        DoorSessionState state = doors.stateFor(ctx.session());
        if (state == null || state.status() == DoorSessionState.Status.DETACHED
                || state.status() == DoorSessionState.Status.ERROR) {
            if (state != null && state.notice() != null) {
                ctx.send(Frames.notify("notifications", state.notice(), "warn", 2500));
            }
            doors.clearState(ctx.session());
            ctx.session().setCurrentDoorId(null);
            ctx.pop();
            return Transition.None.INSTANCE;
        }
        paint(ctx, state);
        return Transition.None.INSTANCE;
    }

    @Override
    public Transition onKey(BbsContext ctx, String key) {
        doors.sendInputKey(ctx.session(), key);
        return Transition.None.INSTANCE;
    }

    @Override
    public Transition onLine(BbsContext ctx, String text) {
        doors.sendInputLine(ctx.session(), text);
        return Transition.None.INSTANCE;
    }

    @Override
    public Transition onCancel(BbsContext ctx) {
        // In door mode Esc belongs to the door protocol first, not the
        // surrounding BBS navigator. This lets doors implement modal
        // sub-screens (like Cityline dialogue) and handle walk-away /
        // back-out interactions themselves.
        doors.sendInputKey(ctx.session(), "ESCAPE");
        return Transition.None.INSTANCE;
    }

    private void paint(BbsContext ctx, DoorSessionState state) {
        ctx.send(Frames.update("banner", 1, Banner.minimalRows("DOOR · " + state.doorName())));
        ctx.send(Frames.update("main", state.version(), state.rows()));
        ctx.send(new InputPrompt(
                state.prompt().mode(),
                state.prompt().label(),
                state.prompt().maxLength(),
                state.prompt().validKeys(),
                null));
    }
}
