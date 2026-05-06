package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.instance.InstanceFeature;
import io.aeyer.voidcore.instance.InstanceFeatureService;
import io.aeyer.voidcore.instance.InstanceFeatureState;
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
public class SysopScreenTogglesScreen implements Screen {

    @Override public Phase phase() { return Phase.SYSOP_SCREEN_TOGGLES; }
    @Override public String name() { return "sysop-screen-toggles"; }

    @Override
    public List<String> topics(BbsContext ctx) {
        return List.of(InstanceFeatureService.TOPIC);
    }

    @Override
    public Transition onEnter(BbsContext ctx) {
        if (!ctx.isSysop()) { ctx.pop(); return Transition.None.INSTANCE; }
        ctx.persistCurrentScreen("{\"kind\":\"sysop_screen_toggles\"}");

        List<InstanceFeatureState> states = ctx.services().instanceFeatures().list();
        ArrayList<Row> rows = new ArrayList<>();
        rows.add(Frames.colored(0, "  == SCREEN TOGGLES ==", "bright_yellow"));
        rows.add(Frames.blank(1));
        for (int i = 0; i < states.size(); i++) {
            InstanceFeatureState state = states.get(i);
            rows.add(Frames.row(2 + i,
                    Frames.span("  [", "grey"),
                    Frames.span(String.valueOf(i + 1), "bright_yellow", true),
                    Frames.span("] ", "grey"),
                    Frames.span(padRight(state.feature().label(), 18), "default"),
                    Frames.span(state.enabled() ? "enabled" : "disabled",
                            state.enabled() ? "bright_green" : "bright_red")));
        }
        int footer = 3 + states.size();
        rows.add(Frames.blank(footer));
        rows.add(Frames.row(footer + 1,
                Frames.span("  pick a number to toggle, or [", "grey"),
                Frames.span("Q", "bright_yellow", true),
                Frames.span("] back", "grey")));
        ctx.send(Frames.update("main", 78, rows));

        StringBuilder valid = new StringBuilder("Q");
        for (int i = 0; i < Math.min(9, states.size()); i++) valid.append(i + 1);
        ctx.send(new InputPrompt("keystroke", "screens:", null, valid.toString(), null));
        return Transition.None.INSTANCE;
    }

    @Override
    public Transition onKey(BbsContext ctx, String key) {
        if ("Q".equals(key)) {
            ctx.pop();
            return Transition.None.INSTANCE;
        }
        if (key.length() == 1 && Character.isDigit(key.charAt(0))) {
            int idx = Character.digit(key.charAt(0), 10);
            List<InstanceFeatureState> states = ctx.services().instanceFeatures().list();
            if (idx >= 1 && idx <= Math.min(9, states.size())) {
                InstanceFeatureState state = states.get(idx - 1);
                boolean next = !state.enabled();
                ctx.services().instanceFeatures().setEnabled(state.feature(), next);
                ctx.publish(InstanceFeatureService.TOPIC);
                ctx.audit(next ? "enable_screen_feature" : "disable_screen_feature",
                        ctx.services().json().createObjectNode()
                                .put("feature", state.feature().slug())
                                .put("enabled", next));
                ctx.send(Frames.notify("notifications",
                        state.feature().label() + " " + (next ? "enabled" : "disabled"),
                        "info", 2500));
                onEnter(ctx);
            }
        }
        return Transition.None.INSTANCE;
    }

    private static String padRight(String value, int width) {
        if (value.length() >= width) return value;
        return value + " ".repeat(width - value.length());
    }
}
