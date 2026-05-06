package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.acl.AclPermission;
import io.aeyer.voidcore.acl.AclResourceType;
import io.aeyer.voidcore.acl.AclService;
import io.aeyer.voidcore.auth.RateLimitDecision;
import io.aeyer.voidcore.auth.RateLimiter;
import io.aeyer.voidcore.instance.InstanceFeature;
import io.aeyer.voidcore.oneliners.Oneliner;
import io.aeyer.voidcore.oneliners.OnelinerRepository;
import io.aeyer.voidcore.ws.VoidCoreSession;
import io.aeyer.voidcore.ws.flow.Frames;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.flow.screen.Screen;
import io.aeyer.voidcore.ws.flow.screen.ScreenText;
import io.aeyer.voidcore.ws.flow.screen.Transition;
import io.aeyer.voidcore.ws.protocol.ServerMessage;
import io.aeyer.voidcore.ws.protocol.ServerMessage.InputPrompt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.aeyer.voidcore.ws.flow.screen.ScreenComponent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Oneliners wall — line-mode submit posts a new entry; Esc returns to
 * the main menu via {@code ctx.pop()}.
 *
 * <p>v1.4 PR-B step 8: rendering moved out of {@code ScreenRouter}.
 *
 * <p>v1.4 PR-B step 13: cross-session live-update goes through the
 * {@link io.aeyer.voidcore.ws.flow.bus.MessageBus}. {@link #topics}
 * declares {@code "oneliners"}; {@link #onEvent} re-paints the wall
 * <em>without</em> re-emitting the {@code InputPrompt} so a peer
 * mid-typing doesn't get their input field clobbered.
 *
 * <p>v1.4 PR-B step 13b: submit logic moved here from
 * {@code ScreenRouter.handleOnelinerSubmit}. The screen now owns the
 * full lifecycle — render, validate, rate-limit, persist, publish,
 * mention. The legacy bridge is gone.
 */
@ScreenComponent
public class OnelinersScreen implements Screen {

    private static final Logger log = LoggerFactory.getLogger(OnelinersScreen.class);
    private static final int ONELINER_MAX_LEN = 70;
    public static final String TOPIC = "oneliners";
    public static final long WALL_ID = 1L;

    private final OnelinerRepository oneliners;
    private final AclService acl;
    private final RateLimiter rateLimiter;
    private final io.aeyer.voidcore.social.ReactionRepository reactions;

    public OnelinersScreen(OnelinerRepository oneliners,
                           AclService acl,
                           RateLimiter rateLimiter,
                           org.springframework.beans.factory.ObjectProvider<io.aeyer.voidcore.social.ReactionRepository> reactions) {
        this.oneliners = oneliners;
        this.acl = acl;
        this.rateLimiter = rateLimiter;
        this.reactions = reactions.getIfAvailable();
    }

    @Override public Phase phase() { return Phase.ONELINERS; }
    @Override public String name() { return "oneliners"; }

    @Override
    public List<String> topics(BbsContext ctx) {
        return ScreenFeatureGate.withTopic(List.of(TOPIC));
    }

    @Override
    public Transition onEvent(BbsContext ctx, String topic) {
        if (!ScreenFeatureGate.enabled(ctx, InstanceFeature.ONELINERS)) {
            ctx.send(Frames.notify("notifications",
                    "the one-liners wall is disabled on this board", "warn", 3000));
            ctx.pop();
            return Transition.None.INSTANCE;
        }
        if (!canView(ctx)) {
            ctx.send(Frames.notify("notifications",
                    "you no longer have access to the one-liners wall", "warn", 3000));
            ctx.pop();
            return Transition.None.INSTANCE;
        }
        // Re-paint only — never re-emit the InputPrompt on peer
        // notifications, or a peer mid-typing would lose their input.
        // The writer's own prompt is sent by onLine after publishing.
        renderFrame(ctx.session());
        return Transition.None.INSTANCE;
    }

    @Override
    public Transition onEnter(BbsContext ctx) {
        if (!ScreenFeatureGate.ensureEnabled(ctx, InstanceFeature.ONELINERS, "one-liners")) {
            return Transition.None.INSTANCE;
        }
        if (!canView(ctx)) {
            ctx.send(Frames.notify("notifications",
                    "you do not have access to the one-liners wall", "warn", 3000));
            ctx.pop();
            return Transition.None.INSTANCE;
        }
        ctx.persistCurrentScreen("{\"kind\":\"oneliners\"}");
        renderFrame(ctx.session());
        emitPrompt(ctx);
        return Transition.None.INSTANCE;
    }

    /**
     * Paint the wall to the given session. Public so the bus delivery
     * path ({@link #onEvent}) can repaint each subscriber, and so
     * {@link #onEnter} can paint the active user. Doesn't emit an
     * InputPrompt — only the active user gets a prompt, via
     * {@link #emitPrompt(BbsContext)}.
     */
    public void renderFrame(VoidCoreSession session) {
        List<Oneliner> list = oneliners.recent(40);
        ArrayList<ServerMessage.Row> rows = new ArrayList<>();
        rows.add(Frames.colored(0, "  == ONE-LINERS ==   " + list.size() + " posted",
                "bright_yellow"));
        rows.add(Frames.blank(1));
        int rowN = 2;
        // Newest at the top — feels more vital than scrolling for the latest.
        // #88: number each row so users can target it with /react N kind.
        // Cap numbering at 9 (single-digit references); rows beyond that
        // are visible but not directly reactable.
        int n = 1;
        for (Oneliner o : list) {
            String when = o.postedAt() == null ? "" : o.postedAt().toString().substring(11, 16);
            String num = (n <= 9) ? ("[" + n + "] ") : "    ";
            String tally = renderTally(o.id());
            rows.add(Frames.row(rowN++,
                    Frames.span("  ", null),
                    Frames.span(num, "bright_yellow"),
                    Frames.span(when, "dark_grey"),
                    Frames.span("  ", null),
                    Frames.span(ScreenText.padRight(o.handle(), 12), "bright_cyan"),
                    Frames.span(o.body(), "default"),
                    Frames.span(tally, "grey")));
            n++;
        }
        if (list.isEmpty()) {
            rows.add(Frames.colored(rowN, "  (the wall is empty — be the first)", "dark_grey"));
        } else {
            rows.add(Frames.blank(rowN++));
            rows.add(Frames.colored(rowN++,
                    "  -- type /react N kind  to react (kinds: like / heart / fire / lol)",
                    "dark_grey"));
        }
        try {
            session.send(Frames.update("main", 40, rows));
        } catch (IOException e) {
            log.debug("oneliner frame send failed for session={}: {}", session.id(), e.toString());
        }
    }

    private void emitPrompt(BbsContext ctx) {
        String prompt = canPost(ctx)
                ? "say something (max 70 chars, [Esc] to return):"
                : "read-only wall ([Esc] to return):";
        ctx.send(new InputPrompt("line",
                prompt,
                ONELINER_MAX_LEN, null, null));
    }

    /**
     * #88: render a one-line tally string for the oneliner — e.g.
     * {@code "  [3 like, 1 fire]"}. Empty if no reactions or repo
     * absent.
     */
    private String renderTally(long onelinerId) {
        if (reactions == null) return "";
        var tallies = reactions.talliesFor("oneliner", onelinerId);
        if (tallies.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("  [");
        boolean first = true;
        for (var t : tallies) {
            if (!first) sb.append(", ");
            sb.append(t.count()).append(' ').append(t.reaction());
            first = false;
        }
        sb.append(']');
        return sb.toString();
    }

    /**
     * Allowed reaction kinds. Single-word tokens to keep the line
     * grammar simple; the picker UI for arbitrary unicode emoji is a
     * future polish ticket.
     */
    private static final java.util.Set<String> REACTION_KINDS =
            java.util.Set.of("like", "heart", "fire", "lol");

    /**
     * Parse and apply {@code /react N kind}. Returns true if the
     * line was a reaction command (handled — caller should not treat
     * it as a oneliner submission), false otherwise.
     */
    private boolean handleReactCommand(BbsContext ctx, String line) {
        if (line == null || !line.startsWith("/react")) return false;
        if (reactions == null) {
            ctx.send(Frames.notify("notifications",
                    "reactions unavailable", "warn", 2000));
            return true;
        }
        if (!canPost(ctx)) {
            ctx.send(Frames.notify("notifications",
                    "you do not have permission to react on the wall", "warn", 3000));
            emitPrompt(ctx);
            return true;
        }
        Long uid = ctx.session().userId();
        if (uid == null) return true;
        String[] parts = line.trim().split("\\s+");
        if (parts.length < 3) {
            ctx.send(Frames.notify("notifications",
                    "usage: /react N kind  (kinds: like / heart / fire / lol)",
                    "warn", 3000));
            return true;
        }
        int n;
        try {
            n = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            ctx.send(Frames.notify("notifications",
                    "/react N must be 1-9", "warn", 2000));
            return true;
        }
        String kind = parts[2].toLowerCase();
        if (!REACTION_KINDS.contains(kind)) {
            ctx.send(Frames.notify("notifications",
                    "unknown reaction: " + kind + " (use like / heart / fire / lol)",
                    "warn", 3000));
            return true;
        }
        java.util.List<io.aeyer.voidcore.oneliners.Oneliner> list = oneliners.recent(40);
        if (n < 1 || n > Math.min(9, list.size())) {
            ctx.send(Frames.notify("notifications",
                    "no oneliner #" + n, "warn", 2000));
            return true;
        }
        long targetId = list.get(n - 1).id();
        // Toggle: if user already has this reaction, remove; else add.
        if (reactions.userReactedWith("oneliner", targetId, uid, kind)) {
            reactions.remove("oneliner", targetId, uid, kind);
            ctx.send(Frames.notify("notifications",
                    "removed " + kind + " from #" + n, "info", 1500));
        } else {
            reactions.add("oneliner", targetId, uid, kind);
            ctx.send(Frames.notify("notifications",
                    kind + "'d #" + n, "info", 1500));
        }
        ctx.publish("oneliners");   // peers see the new tally
        renderFrame(ctx.session());
        emitPrompt(ctx);
        return true;
    }

    @Override
    public Transition onLine(BbsContext ctx, String text) {
        String body = text == null ? "" : text.trim();
        if (body.isEmpty()) {
            // Empty submit just refreshes the wall for the writer
            // (acts like a "redraw"); no other session is affected,
            // so no publish.
            renderFrame(ctx.session());
            emitPrompt(ctx);
            return Transition.None.INSTANCE;
        }
        // #88: /react N kind toggles a reaction on a numbered row.
        // Intercept before the body-length check so the line slash
        // command isn't size-limited like a oneliner body.
        if (body.startsWith("/react") && handleReactCommand(ctx, body)) {
            return Transition.None.INSTANCE;
        }
        if (body.length() > ONELINER_MAX_LEN) {
            ctx.send(Frames.notify("notifications",
                    "one-liner is " + body.length() + " chars — max " + ONELINER_MAX_LEN,
                    "alert", 3000));
            return Transition.None.INSTANCE;
        }
        if (!canPost(ctx)) {
            ctx.send(Frames.notify("notifications",
                    "you do not have permission to post on the wall", "warn", 3000));
            emitPrompt(ctx);
            return Transition.None.INSTANCE;
        }
        Long uid = ctx.session().userId();
        if (uid == null) return Transition.None.INSTANCE;
        var rl = rateLimiter.checkAndRecordPost(uid, RateLimiter.PostKind.ONELINER);
        if (rl instanceof RateLimitDecision.Denied d) {
            long secs = Math.max(1, d.retryAfterMs() / 1000);
            ctx.send(Frames.notify("notifications",
                    "slow down — try again in " + secs + "s", "warn", 3000));
            return Transition.None.INSTANCE;
        }
        long onelinerId = oneliners.insert(uid, body);
        // Bus delivery handles writer + peers: every subscriber to
        // "oneliners" (every session whose top-of-stack is this
        // screen) gets onEvent → renderFrame.
        ctx.publish(TOPIC);
        // Writer needs a fresh prompt because their previous line-mode
        // input has been consumed; peers don't (their cursor stays
        // wherever they were typing).
        emitPrompt(ctx);
        // Targeted notifications for @mentions are a separate semantic
        // (per-user delivery, payloaded) and don't ride the bus —
        // ADR-027.
        ctx.services().mentions().notify(
                ctx.session(), body, "the one-liner wall", Phase.ONELINERS);
        // #87 / #89: append to activity_events for the recent-activity
        // feed and evaluate first-oneliner achievement. Newly-unlocked
        // achievements surface as info notifies on this session.
        if (ctx.services().socialEvents() != null) {
            var awarded = ctx.services().socialEvents().recordEvent(
                    "oneliner.created", uid,
                    ctx.services().json().createObjectNode()
                            .put("oneliner_id", onelinerId));
            for (var a : awarded) {
                ctx.send(Frames.notify("notifications",
                        "★ Achievement unlocked: " + a.name(),
                        "info", 4000));
            }
        }
        return Transition.None.INSTANCE;
    }

    @Override
    public Transition onCancel(BbsContext ctx) {
        ctx.pop();
        return Transition.None.INSTANCE;
    }

    private boolean canView(BbsContext ctx) {
        return acl.can(ctx.session(), AclResourceType.ONELINER_WALL, WALL_ID, AclPermission.VIEW);
    }

    private boolean canPost(BbsContext ctx) {
        return acl.can(ctx.session(), AclResourceType.ONELINER_WALL, WALL_ID, AclPermission.POST);
    }
}
