package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.ws.flow.Frames;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.flow.screen.Screen;
import io.aeyer.voidcore.ws.flow.screen.ScreenComponent;
import io.aeyer.voidcore.ws.flow.screen.Transition;
import io.aeyer.voidcore.ws.protocol.ServerMessage.InputPrompt;
import io.aeyer.voidcore.ws.protocol.ServerMessage.Row;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.impl.DSL;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;

import static io.aeyer.voidcore.jooq.Tables.SYSOP_ACTIONS;
import static io.aeyer.voidcore.jooq.Tables.USERS;

/**
 * #92 sysop audit-log browse screen. Sysop-only — gated on
 * {@code ctx.isSysop()}. Last 50 rows newest-first.
 *
 * <h2>Filters</h2>
 *
 * <p>{@code [F]} opens a single-line filter prompt accepting a
 * power-user expression like
 * <pre>actor=SYSOP action=edit_release_title since=24h</pre>
 *
 * <ul>
 *   <li>{@code actor=<handle>} — exact match on
 *       {@code users.handle}.</li>
 *   <li>{@code action=<text>} — substring match (LIKE-prefix-anywhere)
 *       on {@code action}.</li>
 *   <li>{@code since=24h} / {@code since=7d} — relative window from
 *       now.</li>
 * </ul>
 *
 * <p>Empty filter clears. Unknown keys are silently ignored. Filter
 * state rides on {@link io.aeyer.voidcore.ws.VoidCoreSession#docsFilter}
 * — already a generic filter slot, repurposed here. Cleared on Q.
 */
@ScreenComponent
public class SysopAuditScreen implements Screen {

    private static final int LIMIT = 50;

    private final DSLContext dsl;

    public SysopAuditScreen(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override public Phase phase() { return Phase.SYSOP_AUDIT; }
    @Override public String name() { return "sysop-audit"; }

    @Override
    public Transition onEnter(BbsContext ctx) {
        if (!ctx.isSysop()) {
            ctx.pop();
            return Transition.None.INSTANCE;
        }
        ctx.persistCurrentScreen("{\"kind\":\"sysop_audit\"}");

        AuditFilter filter = AuditFilter.parse(ctx.session().docsFilter());

        var query = dsl.select(SYSOP_ACTIONS.AT, USERS.HANDLE,
                        SYSOP_ACTIONS.ACTION, SYSOP_ACTIONS.PAYLOAD)
                .from(SYSOP_ACTIONS)
                .leftJoin(USERS).on(USERS.ID.eq(SYSOP_ACTIONS.ACTOR_ID))
                .where(filter.toCondition());
        var entries = query
                .orderBy(SYSOP_ACTIONS.AT.desc())
                .limit(LIMIT)
                .fetch();

        ArrayList<Row> rows = new ArrayList<>();
        String header = "  == AUDIT LOG ==   last " + entries.size();
        if (!filter.isEmpty()) header += "  · " + filter.describe();
        rows.add(Frames.colored(0, header, "bright_yellow"));
        rows.add(Frames.blank(1));
        if (entries.isEmpty()) {
            rows.add(Frames.colored(2,
                    filter.isEmpty()
                            ? "  (no audit rows yet)"
                            : "  (no rows match the current filter)",
                    "dark_grey"));
        } else {
            int rowN = 2;
            for (var r : entries) {
                String when = relativeWhen(r.value1());
                String actor = r.value2() == null ? "?" : r.value2();
                String action = r.value3();
                JSONB payloadBlob = r.value4();
                String payload = payloadBlob == null ? "" : payloadBlob.data();
                if (payload.length() > 60) payload = payload.substring(0, 57) + "...";
                rows.add(Frames.row(rowN++,
                        Frames.span("  ", null),
                        Frames.span(DocsCommon.padRight(when, 14), "grey"),
                        Frames.span(DocsCommon.padRight(actor, 14), "default"),
                        Frames.span(DocsCommon.padRight(action, 28), "bright_cyan"),
                        Frames.span(payload, "dark_grey")));
            }
        }
        rows.add(Frames.blank(rows.size()));
        rows.add(Frames.row(rows.size(),
                Frames.span("  [", "grey"),
                Frames.span("F", "bright_yellow", true),
                Frames.span("] filter  [", "grey"),
                Frames.span("X", "bright_yellow", true),
                Frames.span("] clear  [", "grey"),
                Frames.span("Q", "bright_yellow", true),
                Frames.span("] back to sysop menu", "grey")));

        ctx.send(Frames.update("main", 76, rows));
        ctx.send(new InputPrompt("keystroke", "audit:", null, "FXQ", null));
        return Transition.None.INSTANCE;
    }

    @Override
    public Transition onKey(BbsContext ctx, String k) {
        switch (k) {
            case "Q" -> {
                ctx.session().setDocsFilter(null);  // don't leak audit filter into docs
                ctx.pop();
            }
            case "X" -> {
                ctx.session().setDocsFilter(null);
                onEnter(ctx);  // re-render unfiltered
            }
            case "F" -> {
                // Send a line prompt; the next inbound LINE submit
                // (handled in onLine below) parses + applies.
                ctx.send(new InputPrompt("line",
                        "filter (e.g. actor=SYSOP action=ban since=24h, blank to clear):",
                        500, null, null));
            }
            default -> { /* ignored */ }
        }
        return Transition.None.INSTANCE;
    }

    @Override
    public Transition onLine(BbsContext ctx, String text) {
        // Stash the filter expression on docsFilter (repurposed slot)
        // — empty input clears.
        if (text == null || text.isBlank()) {
            ctx.session().setDocsFilter(null);
        } else {
            ctx.session().setDocsFilter(text.trim());
        }
        onEnter(ctx);
        return Transition.None.INSTANCE;
    }

    @Override
    public Transition onCancel(BbsContext ctx) {
        // Esc on the filter prompt — keep current filter, just re-render.
        onEnter(ctx);
        return Transition.None.INSTANCE;
    }

    private static String relativeWhen(OffsetDateTime when) {
        if (when == null) return "?";
        long secs = ChronoUnit.SECONDS.between(when, OffsetDateTime.now());
        if (secs < 60) return secs + "s ago";
        long mins = secs / 60;
        if (mins < 60) return mins + "m ago";
        long hours = mins / 60;
        if (hours < 24) return hours + "h ago";
        long days = hours / 24;
        if (days < 30) return days + "d ago";
        return when.toLocalDate().toString();
    }

    /**
     * Parsed audit-log filter — actor handle / action substring /
     * since-window. Pure value; round-trips through a single string
     * stored on the session for reconnect-safety.
     */
    record AuditFilter(String actorHandle, String actionLike,
                       OffsetDateTime since) {

        static final AuditFilter EMPTY = new AuditFilter(null, null, null);

        boolean isEmpty() {
            return actorHandle == null && actionLike == null && since == null;
        }

        static AuditFilter parse(String raw) {
            if (raw == null || raw.isBlank()) return EMPTY;
            String actor = null, action = null;
            OffsetDateTime since = null;
            for (String tok : raw.trim().split("\\s+")) {
                int eq = tok.indexOf('=');
                if (eq < 0) continue;
                String key = tok.substring(0, eq);
                String val = tok.substring(eq + 1);
                if (val.isEmpty()) continue;
                switch (key) {
                    case "actor" -> actor = val;
                    case "action" -> action = val;
                    case "since" -> since = parseSince(val);
                    default -> { /* ignore unknown */ }
                }
            }
            return new AuditFilter(actor, action, since);
        }

        private static OffsetDateTime parseSince(String val) {
            if (val.length() < 2) return null;
            char unit = val.charAt(val.length() - 1);
            try {
                int n = Integer.parseInt(val.substring(0, val.length() - 1));
                return switch (unit) {
                    case 'h' -> OffsetDateTime.now().minusHours(n);
                    case 'd' -> OffsetDateTime.now().minusDays(n);
                    case 'm' -> OffsetDateTime.now().minusMinutes(n);
                    default -> null;
                };
            } catch (NumberFormatException e) {
                return null;
            }
        }

        Condition toCondition() {
            Condition c = DSL.noCondition();
            if (actorHandle != null) {
                c = c.and(USERS.HANDLE.equalIgnoreCase(actorHandle));
            }
            if (actionLike != null) {
                c = c.and(SYSOP_ACTIONS.ACTION.likeIgnoreCase("%" + actionLike + "%"));
            }
            if (since != null) {
                c = c.and(SYSOP_ACTIONS.AT.ge(since));
            }
            return c;
        }

        String describe() {
            StringBuilder sb = new StringBuilder();
            if (actorHandle != null) sb.append("actor=").append(actorHandle).append(' ');
            if (actionLike != null) sb.append("action=").append(actionLike).append(' ');
            if (since != null) sb.append("since=").append(since.toLocalDate());
            return sb.toString().trim();
        }
    }
}
