package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.atmosphere.FortuneRepository;
import io.aeyer.voidcore.auth.LoginSummary;
import io.aeyer.voidcore.auth.LoginSummaryService;
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

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Ticket #85 — "what's new since your last call" screen. Pushed by
 * {@code AuthFinaliser} on fresh login (post auth.ok, before
 * applyPostAuth's MENU push) when the computed
 * {@link LoginSummary} has non-zero counts.
 *
 * <p>Letters double as deep-link shortcuts using the same letter
 * mappings as {@code MenuScreen} so muscle memory carries over:
 * <ul>
 *   <li>{@code B} → {@code BULLETINS_LIST}</li>
 *   <li>{@code F} → {@code RELEASES_LIST}</li>
 *   <li>{@code I} → {@code DOCS_HUB}</li>
 *   <li>{@code M} → {@code BASES_LIST}</li>
 *   <li>{@code N} → {@code NETMAIL_INBOX}</li>
 *   <li>{@code O} → {@code ONELINERS}</li>
 *   <li>{@code Q} or {@code ENTER} → pop to menu (which is the
 *       phase below this on the stack)</li>
 * </ul>
 *
 * <p>The summary is recomputed on every {@code onEnter} so a peer's
 * activity between dismiss-and-reenter is visible. Cheap — five
 * count queries, all GIN-/B-tree indexed.
 */
@ScreenComponent
public class LoginSummaryScreen implements Screen {

    private final LoginSummaryService summaries;
    private final FortuneRepository fortunes;

    public LoginSummaryScreen(LoginSummaryService summaries,
                              org.springframework.beans.factory.ObjectProvider<FortuneRepository> fortunes) {
        this.summaries = summaries;
        this.fortunes = fortunes.getIfAvailable();
    }

    @Override public Phase phase() { return Phase.LOGIN_SUMMARY; }
    @Override public String name() { return "login-summary"; }

    @Override
    public List<String> topics(BbsContext ctx) {
        return List.of(InstanceFeatureService.TOPIC);
    }

    @Override
    public Transition onEnter(BbsContext ctx) {
        Long uid = ctx.session().userId();
        Instant prev = ctx.session().previousLastCall();
        if (uid == null) {
            ctx.pop();
            return Transition.None.INSTANCE;
        }
        LoginSummary s = summaries.compute(uid,
                prev == null ? null : prev.atOffset(ZoneOffset.UTC));
        if (s.isEmpty()) {
            // Edge: deltas drained between the AuthFinaliser push and
            // first paint. Bail out gracefully.
            ctx.pop();
            return Transition.None.INSTANCE;
        }
        ctx.persistCurrentScreen("{\"kind\":\"login_summary\"}");

        ArrayList<Row> rows = new ArrayList<>();
        String header = "  == since you were last here";
        if (prev != null) {
            header += ", " + relativeWhen(prev);
        }
        header += " ==";
        rows.add(Frames.colored(0, header, "bright_yellow"));
        rows.add(Frames.blank(1));
        int rowN = 2;
        boolean announcements = ScreenFeatureGate.enabled(ctx, InstanceFeature.ANNOUNCEMENTS);
        boolean files = ScreenFeatureGate.enabled(ctx, InstanceFeature.FILES);
        boolean boards = ScreenFeatureGate.enabled(ctx, InstanceFeature.MESSAGE_BOARD);
        boolean voidmail = ScreenFeatureGate.enabled(ctx, InstanceFeature.VOIDMAIL);
        boolean oneliners = ScreenFeatureGate.enabled(ctx, InstanceFeature.ONELINERS);
        if (announcements && s.newArticles() > 0) {
            rows.add(deltaRow(rowN++, "B", s.newArticles(),
                    "new announcement",   "announcements"));
        }
        if (files && s.newReleases() > 0) {
            rows.add(deltaRow(rowN++, "F", s.newReleases(),
                    "new file",    "files"));
        }
        if (boards && s.newThreads() > 0) {
            rows.add(deltaRow(rowN++, "M", s.newThreads(),
                    "new thread",     "threads"));
        }
        if (voidmail && s.unreadNetmail() > 0) {
            rows.add(deltaRow(rowN++, "N", s.unreadNetmail(),
                    "unread voidmail", "unread voidmail"));
        }
        if (oneliners && s.newOneliners() > 0) {
            rows.add(deltaRow(rowN++, "O", s.newOneliners(),
                    "new oneliner",   "oneliners since you read the wall"));
        }
        rows.add(Frames.blank(rowN++));
        rows.add(Frames.row(rowN++,
                Frames.span("  press a letter to jump there, or [", "grey"),
                Frames.span("Q", "bright_yellow", true),
                Frames.span("] to continue to main menu", "grey")));

        // #93 atmosphere: a fortune at the bottom of the summary.
        // Skipped if the FortuneRepository couldn't pull one (empty
        // table, DB-less test profile, etc.).
        if (fortunes != null) {
            String fortune = fortunes.random().orElse(null);
            if (fortune != null) {
                rows.add(Frames.blank(rowN++));
                rows.add(Frames.colored(rowN++, "  -- " + fortune, "dark_grey"));
            }
        }

        ctx.send(Frames.update("main", 76, rows));
        ctx.send(new InputPrompt("keystroke", "summary:", null,
                validKeysFor(s, announcements, files, boards, voidmail, oneliners), null));
        return Transition.None.INSTANCE;
    }

    private static Row deltaRow(int rowN, String letter, long count,
                                String singular, String pluralPhrase) {
        String label = count + " " + (count == 1 ? singular : pluralPhrase);
        return Frames.row(rowN,
                Frames.span("  [", "grey"),
                Frames.span(letter, "bright_yellow", true),
                Frames.span("] ", "grey"),
                Frames.span(label, "default"));
    }

    private static String validKeysFor(LoginSummary s,
                                       boolean announcements,
                                       boolean files,
                                       boolean boards,
                                       boolean voidmail,
                                       boolean oneliners) {
        StringBuilder sb = new StringBuilder();
        if (announcements && s.newArticles() > 0) sb.append('B');
        if (files && s.newReleases() > 0) sb.append('F');
        if (boards && s.newThreads() > 0) sb.append('M');
        if (voidmail && s.unreadNetmail() > 0) sb.append('N');
        if (oneliners && s.newOneliners() > 0) sb.append('O');
        sb.append('Q');
        return sb.toString();
    }

    @Override
    public Transition onKey(BbsContext ctx, String k) {
        switch (k) {
            case "Q" -> ctx.pop();
            case "B" -> { if (ScreenFeatureGate.enabled(ctx, InstanceFeature.ANNOUNCEMENTS)) jumpTo(ctx, Phase.BULLETINS_LIST); }
            case "F" -> { if (ScreenFeatureGate.enabled(ctx, InstanceFeature.FILES)) jumpTo(ctx, Phase.RELEASES_LIST); }
            case "I" -> { if (ScreenFeatureGate.enabled(ctx, InstanceFeature.INFO_DOCS)) jumpTo(ctx, Phase.DOCS_HUB); }
            case "M" -> { if (ScreenFeatureGate.enabled(ctx, InstanceFeature.MESSAGE_BOARD)) jumpTo(ctx, Phase.BASES_LIST); }
            case "N" -> { if (ScreenFeatureGate.enabled(ctx, InstanceFeature.VOIDMAIL)) jumpTo(ctx, Phase.NETMAIL_INBOX); }
            case "O" -> { if (ScreenFeatureGate.enabled(ctx, InstanceFeature.ONELINERS)) jumpTo(ctx, Phase.ONELINERS); }
            default -> { /* ignored */ }
        }
        return Transition.None.INSTANCE;
    }

    /**
     * Replace the summary on the stack with the target phase so the
     * back-stack looks like {@code [MENU, target]} — exactly as if
     * the user had landed on MENU and pressed the letter directly.
     * Without {@code replaceTop}, the stack would be
     * {@code [MENU, LOGIN_SUMMARY, target]} and {@code [Q]}-back
     * from {@code target} would re-enter the summary, which is
     * jarring (the deltas have presumably been read once).
     */
    private void jumpTo(BbsContext ctx, Phase target) {
        ctx.replaceTopAndEnter(target);
    }

    private static String relativeWhen(Instant when) {
        long minutes = ChronoUnit.MINUTES.between(
                when.atOffset(ZoneOffset.UTC), OffsetDateTime.now());
        if (minutes < 1) return "just now";
        if (minutes < 60) return minutes + (minutes == 1 ? " minute ago" : " minutes ago");
        long hours = minutes / 60;
        if (hours < 24) return hours + (hours == 1 ? " hour ago" : " hours ago");
        long days = hours / 24;
        if (days < 30) return days + (days == 1 ? " day ago" : " days ago");
        return when.atOffset(ZoneOffset.UTC).toLocalDate().toString();
    }
}
