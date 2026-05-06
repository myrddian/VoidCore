package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.instance.InstanceFeature;
import io.aeyer.voidcore.instance.InstanceFeatureService;
import io.aeyer.voidcore.ws.flow.Frames;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;

import java.util.ArrayList;
import java.util.List;

final class ScreenFeatureGate {

    private ScreenFeatureGate() {}

    static boolean enabled(BbsContext ctx, InstanceFeature feature) {
        return ctx.services().instanceFeatures().enabled(feature);
    }

    static boolean ensureEnabled(BbsContext ctx, InstanceFeature feature, String label) {
        if (enabled(ctx, feature)) return true;
        ctx.send(Frames.notify("notifications",
                label + " is disabled on this board", "warn", 3000));
        ctx.pop();
        return false;
    }

    static List<String> withTopic(List<String> topics) {
        ArrayList<String> all = new ArrayList<>(topics);
        all.add(InstanceFeatureService.TOPIC);
        return all;
    }
}
