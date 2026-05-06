package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.ws.flow.Frames;
import io.aeyer.voidcore.ws.flow.layout.Element;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.flow.screen.ScreenApp;
import io.aeyer.voidcore.ws.flow.screen.ScreenAppComponent;
import io.aeyer.voidcore.ws.flow.screen.Transition;
import io.aeyer.voidcore.ws.flow.ui.AppEvent;
import io.aeyer.voidcore.ws.protocol.ServerMessage;

import java.util.List;

/**
 * Sysop · broadcast — single-input ScreenApp. Renders a TextField;
 * on field.commit, calls MentionService.broadcastAll and pops back
 * to the sysop menu.
 */
@ScreenAppComponent
public class SysopBroadcastScreen extends ScreenApp {

    @Override public Phase phase() { return Phase.SYSOP_BROADCAST; }
    @Override public String name() { return "sysop-broadcast"; }

    @Override
    protected String appKey(BbsContext ctx) { return "sysop-broadcast"; }

    @Override
    protected String bannerLabel(BbsContext ctx) { return "SYSOP/BROADCAST"; }

    @Override
    protected Element compose(BbsContext ctx) {
        return new Element.VStack(List.of(
            new Element.Header("SYSOP · BROADCAST", null),
            new Element.Spacer(1),
            new Element.Para("Sends an alert-styled notify to every open authenticated session."),
            new Element.Spacer(1),
            new Element.Form("broadcast-form", List.of(
                new Element.TextField("text", "broadcast:", "", 512, false)
            ), "text")
        ), 0);
    }

    @Override
    protected ServerMessage.InputPrompt defaultInputPrompt(BbsContext ctx) {
        return new ServerMessage.InputPrompt("none", null, null, null, null);
    }

    @Override
    protected void onEvent(BbsContext ctx, AppEvent ev) {
        if (ev instanceof AppEvent.FieldCommit fc && "text".equals(fc.widgetId())) {
            String body = fc.value() == null ? "" : fc.value().trim();
            if (body.isEmpty()) {
                popAndExit(ctx);
                return;
            }
            String full = "SYSOP: " + body;
            int sent = ctx.services().mentions().broadcastAll(full, 6000);
            ctx.audit("broadcast",
                ctx.services().json().createObjectNode()
                    .put("text", body).put("sessions", sent));
            ctx.send(Frames.notify("notifications",
                "broadcast sent to " + sent + " session" + (sent == 1 ? "" : "s"),
                "info", 3000));
            popAndExit(ctx);
        } else if (ev instanceof AppEvent.FieldCancel) {
            popAndExit(ctx);
        } else if (ev instanceof AppEvent.FocusMove) {
            popAndExit(ctx);
        }
    }

    @Override
    public Transition onCancel(BbsContext ctx) {
        popAndExit(ctx);
        return Transition.None.INSTANCE;
    }
}
