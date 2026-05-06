package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.documents.DocumentFilter;
import io.aeyer.voidcore.documents.DocumentRow;
import io.aeyer.voidcore.ws.VoidCoreSession;
import io.aeyer.voidcore.ws.flow.Frames;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.protocol.ServerMessage;
import io.aeyer.voidcore.ws.protocol.ServerMessage.Row;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Shared rendering / filter-state plumbing for the PR-5 documents
 * faceted-nav screens. All six screens (hub, four pickers, results)
 * read and write the session-side {@code docsFilter} string and
 * format document rows the same way.
 */
final class DocsCommon {

    private static final Logger log = LoggerFactory.getLogger(DocsCommon.class);

    private DocsCommon() {}

    /** How many docs per page on the results screen. */
    static final int PAGE_SIZE = 20;

    /** How many recent docs to show on the hub landing. */
    static final int HUB_RECENT_COUNT = 5;

    /** Top-N for tag and author pickers. */
    static final int PICKER_TOP_N = 50;

    static final String ROOT_PATH = "DOCS:/";

    // ─── Filter state plumbing ────────────────────────────────────────────

    /**
     * Read the session's {@code docsFilter} string and parse it. On
     * malformed input (which shouldn't happen — the screens always
     * write canonical form) logs a warning and returns
     * {@link DocumentFilter#empty}.
     */
    static DocumentFilter currentFilter(VoidCoreSession session) {
        String s = session.docsFilter();
        if (s == null || s.isBlank()) return DocumentFilter.empty();
        try {
            return DocumentFilter.parse(s);
        } catch (IllegalArgumentException e) {
            log.warn("malformed docsFilter on session {} ({}): {}",
                    session.id(), s, e.toString());
            session.setDocsFilter(null);
            return DocumentFilter.empty();
        }
    }

    /** Persist a filter back to the session in canonical form. */
    static void writeFilter(VoidCoreSession session, DocumentFilter filter) {
        if (filter == null || filter.isEmpty()) {
            session.setDocsFilter(null);
        } else {
            session.setDocsFilter(filter.serialise());
        }
    }

    // ─── Document row rendering ───────────────────────────────────────────

    /**
     * One numbered row in a doc list. {@code n} is 1-based, max 9
     * (single-digit prompt). Format:
     * <pre>  [N] title                       handle      when</pre>
     *
     * <p>Title is what users think of as the document name; the slug
     * (e.g. {@code "untitled-1777778433957"}) is opaque and shouldn't
     * lead the row. The numeric id appears in the banner when the
     * document is opened, providing an unambiguous reference.
     */
    static Row docListRow(int rowN, int n, DocumentRow doc, String authorHandle) {
        String num = "[" + n + "]";
        String rawTitle = doc.title() == null || doc.title().isBlank()
                ? "(untitled)"
                : doc.title();
        String title = padRight(rawTitle, 32);
        String author = padRight(authorHandle == null ? "?" : authorHandle, 14);
        String when = formatWhen(doc.updatedAt());
        return Frames.row(rowN,
                Frames.span("  ", null),
                Frames.span(num, "bright_yellow", true),
                Frames.span(" ", null),
                Frames.span(title, "default"),
                Frames.span(" ", null),
                Frames.span(author, "grey"),
                Frames.span(" ", null),
                Frames.span(when, "grey"));
    }

    /** Header row showing total count, e.g. {@code "240 documents"}. */
    static Row totalRow(int rowN, long total, String breadcrumb) {
        String left = "  == " + displayPath(breadcrumb) + " ==";
        String right = total + " document" + (total == 1 ? "" : "s");
        // Pad left to align right text at column ~50.
        int targetCol = 60;
        int pad = Math.max(1, targetCol - left.length() - right.length());
        return Frames.row(rowN,
                Frames.span(left, "bright_yellow", true),
                Frames.span(" ".repeat(pad), null),
                Frames.span(right, "grey"));
    }

    static Row pageHeaderRow(int rowN, long total, int pageZeroIdx, int totalPages, String breadcrumb) {
        String left = "  == " + displayPath(breadcrumb) + " ==";
        String right = total + " doc" + (total == 1 ? "" : "s")
                + " · page " + (pageZeroIdx + 1) + "/" + Math.max(1, totalPages);
        int targetCol = 70;
        int pad = Math.max(1, targetCol - left.length() - right.length());
        return Frames.row(rowN,
                Frames.span(left, "bright_yellow", true),
                Frames.span(" ".repeat(pad), null),
                Frames.span(right, "grey"));
    }

    static String formatWhen(OffsetDateTime when) {
        if (when == null) return "?";
        long minutes = ChronoUnit.MINUTES.between(when, OffsetDateTime.now());
        if (minutes < 1) return "just now";
        if (minutes < 60) return minutes + (minutes == 1 ? " min ago" : " mins ago");
        long hours = minutes / 60;
        if (hours < 24) return hours + (hours == 1 ? " hour ago" : " hours ago");
        long days = hours / 24;
        if (days < 30) return days + (days == 1 ? " day ago" : " days ago");
        return when.toLocalDate().toString();
    }

    static String padRight(String s, int width) {
        if (s == null) s = "";
        if (s.length() >= width) return s.substring(0, Math.max(0, width - 1)) + "…";
        return s + " ".repeat(width - s.length());
    }

    /**
     * Numbered-keystroke valid_keys for an N-doc page: "123..." up to
     * the actual count, plus a separator-and-other-letters tail.
     */
    static String numberedKeys(int count, String tail) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= Math.min(count, 9); i++) sb.append(i);
        if (tail != null) sb.append(tail);
        return sb.toString();
    }

    static String displayPath(String breadcrumb) {
        if (breadcrumb == null || breadcrumb.isBlank()) return ROOT_PATH;
        return ROOT_PATH + toVirtualPath(breadcrumb);
    }

    static String facetPath(String dir) {
        return ROOT_PATH + dir + "/";
    }

    private static String toVirtualPath(String breadcrumb) {
        StringBuilder out = new StringBuilder();
        for (String segment : breadcrumb.split("/")) {
            int eq = segment.indexOf('=');
            if (eq < 0) continue;
            String key = segment.substring(0, eq);
            String value = segment.substring(eq + 1);
            switch (key) {
                case "kind" -> out.append("by-kind/").append(value).append('/');
                case "tag" -> out.append("by-tag/").append(value).append('/');
                case "-tag" -> out.append("without-tag/").append(value).append('/');
                case "by" -> out.append("by-author/").append(value).append('/');
                case "when" -> out.append("by-year/").append(value).append('/');
                case "search" -> out.append("search/").append(value).append('/');
                default -> out.append(key).append('/').append(value).append('/');
            }
        }
        return out.toString();
    }

    /** Notify text helper. */
    static ServerMessage comingSoon(String label) {
        return Frames.notify("notifications",
                "(coming soon — " + label + ")", "info", 2000);
    }

    // ─── Layout helpers ───────────────────────────────────────────────────

    static Row blank(int rowN) { return Frames.blank(rowN); }
}
