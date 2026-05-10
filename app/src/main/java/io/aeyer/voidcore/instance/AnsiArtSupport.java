package io.aeyer.voidcore.instance;

import io.aeyer.voidcore.ws.protocol.ServerMessage.Row;
import io.aeyer.voidcore.ws.protocol.ServerMessage.Span;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

final class AnsiArtSupport {

    private AnsiArtSupport() {}

    static Canvas parse(String text) {
        Canvas canvas = new Canvas(1, 0);
        Style style = new Style(null, null, false);
        int row = 0;
        int col = 0;
        int i = 0;
        while (i < text.length()) {
            char ch = text.charAt(i);
            if (ch == '\u001b' && i + 1 < text.length() && text.charAt(i + 1) == '[') {
                int end = findCsiTerminator(text, i + 2);
                if (end > 0 && text.charAt(end) == 'm') {
                    style = applySgr(style, text.substring(i + 2, end));
                    i = end + 1;
                    continue;
                }
            }
            if (ch == '\r') {
                col = 0;
                i++;
                continue;
            }
            if (ch == '\n') {
                row++;
                col = 0;
                canvas.ensureSize(row + 1, canvas.width());
                i++;
                continue;
            }
            canvas.put(row, col, new Cell(ch, style.fg(), style.bg(), style.bold()));
            col++;
            i++;
        }
        return canvas;
    }

    static Canvas fromRows(List<Row> rows) {
        int height = rows.stream().mapToInt(Row::row).max().orElse(-1) + 1;
        int width = 0;
        for (Row row : rows) {
            width = Math.max(width, spanWidth(row.spans()));
        }
        Canvas canvas = new Canvas(Math.max(height, 1), width);
        for (Row row : rows) {
            int col = 0;
            for (Span span : row.spans()) {
                String text = span.text() == null ? "" : span.text();
                for (int i = 0; i < text.length(); i++) {
                    canvas.put(row.row(), col++, new Cell(
                            text.charAt(i),
                            normalizeStyle(span.fg()),
                            normalizeStyle(span.bg()),
                            Boolean.TRUE.equals(span.bold())));
                }
            }
        }
        return canvas;
    }

    static List<Row> toRows(Canvas canvas) {
        List<Row> out = new ArrayList<>();
        for (int row = 0; row < canvas.height(); row++) {
            List<Cell> line = canvas.row(row);
            int limit = visibleWidth(line);
            if (limit == 0) {
                out.add(new Row(row, List.of(new Span("", null, null, null))));
                continue;
            }
            List<Span> spans = new ArrayList<>();
            StringBuilder text = new StringBuilder();
            Cell previous = null;
            for (int col = 0; col < limit; col++) {
                Cell current = line.get(col);
                if (previous != null && sameStyle(previous, current)) {
                    text.append(current.ch());
                } else {
                    if (previous != null) {
                        spans.add(toSpan(text.toString(), previous));
                    }
                    text.setLength(0);
                    text.append(current.ch());
                    previous = current;
                }
            }
            if (previous != null) spans.add(toSpan(text.toString(), previous));
            out.add(new Row(row, spans));
        }
        return out;
    }

    private static Span toSpan(String text, Cell cell) {
        return new Span(text, cell.fg(), cell.bg(), cell.bold() ? Boolean.TRUE : null);
    }

    private static boolean sameStyle(Cell a, Cell b) {
        return Objects.equals(a.fg(), b.fg())
                && Objects.equals(a.bg(), b.bg())
                && a.bold() == b.bold();
    }

    private static int visibleWidth(List<Cell> line) {
        for (int i = line.size() - 1; i >= 0; i--) {
            Cell cell = line.get(i);
            if (cell.ch() != ' ' || cell.bg() != null) return i + 1;
        }
        return 0;
    }

    private static int spanWidth(List<Span> spans) {
        int width = 0;
        for (Span span : spans) width += span.text() == null ? 0 : span.text().length();
        return width;
    }

    private static int findCsiTerminator(String text, int start) {
        for (int i = start; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch >= '@' && ch <= '~') return i;
        }
        return -1;
    }

    private static Style applySgr(Style current, String payload) {
        Style style = current;
        int[] codes = payload == null || payload.isBlank()
                ? new int[] {0}
                : Arrays.stream(payload.split(";"))
                        .map(String::trim)
                        .filter(token -> !token.isEmpty())
                        .mapToInt(token -> {
                            try {
                                return Integer.parseInt(token);
                            } catch (NumberFormatException e) {
                                return -1;
                            }
                        })
                        .toArray();
        for (int code : codes) {
            style = switch (code) {
                case 0 -> new Style(null, null, false);
                case 1 -> new Style(style.fg(), style.bg(), true);
                case 22 -> new Style(style.fg(), style.bg(), false);
                case 30 -> new Style("black", style.bg(), style.bold());
                case 31 -> new Style("red", style.bg(), style.bold());
                case 32 -> new Style("green", style.bg(), style.bold());
                case 33 -> new Style("yellow", style.bg(), style.bold());
                case 34 -> new Style("blue", style.bg(), style.bold());
                case 35 -> new Style("magenta", style.bg(), style.bold());
                case 36 -> new Style("cyan", style.bg(), style.bold());
                case 37 -> new Style("white", style.bg(), style.bold());
                case 39 -> new Style(null, style.bg(), style.bold());
                case 40 -> new Style(style.fg(), "black", style.bold());
                case 41 -> new Style(style.fg(), "red", style.bold());
                case 42 -> new Style(style.fg(), "green", style.bold());
                case 43 -> new Style(style.fg(), "yellow", style.bold());
                case 44 -> new Style(style.fg(), "blue", style.bold());
                case 45 -> new Style(style.fg(), "magenta", style.bold());
                case 46 -> new Style(style.fg(), "cyan", style.bold());
                case 47 -> new Style(style.fg(), "white", style.bold());
                case 49 -> new Style(style.fg(), null, style.bold());
                case 90 -> new Style("grey", style.bg(), style.bold());
                case 91 -> new Style("bright_red", style.bg(), style.bold());
                case 92 -> new Style("bright_green", style.bg(), style.bold());
                case 93 -> new Style("bright_yellow", style.bg(), style.bold());
                case 94 -> new Style("bright_blue", style.bg(), style.bold());
                case 95 -> new Style("bright_magenta", style.bg(), style.bold());
                case 96 -> new Style("bright_cyan", style.bg(), style.bold());
                case 97 -> new Style("bright", style.bg(), style.bold());
                case 100 -> new Style(style.fg(), "grey", style.bold());
                case 101 -> new Style(style.fg(), "bright_red", style.bold());
                case 102 -> new Style(style.fg(), "bright_green", style.bold());
                case 103 -> new Style(style.fg(), "bright_yellow", style.bold());
                case 104 -> new Style(style.fg(), "bright_blue", style.bold());
                case 105 -> new Style(style.fg(), "bright_magenta", style.bold());
                case 106 -> new Style(style.fg(), "bright_cyan", style.bold());
                case 107 -> new Style(style.fg(), "bright", style.bold());
                default -> style;
            };
        }
        return style;
    }

    private static String normalizeStyle(String style) {
        if (style == null) return null;
        String normalized = style.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() || "default".equals(normalized) ? null : normalized;
    }

    record Canvas(List<List<Cell>> rows) {
        Canvas(int height, int width) {
            this(new ArrayList<>());
            ensureSize(height, width);
        }

        int height() {
            return rows.size();
        }

        int width() {
            int width = 0;
            for (List<Cell> row : rows) width = Math.max(width, row.size());
            return width;
        }

        List<Cell> row(int index) {
            ensureSize(index + 1, 0);
            return rows.get(index);
        }

        void ensureSize(int height, int width) {
            while (rows.size() < height) rows.add(new ArrayList<>());
            for (List<Cell> row : rows) {
                while (row.size() < width) row.add(Cell.blank());
            }
        }

        void put(int row, int col, Cell cell) {
            ensureSize(row + 1, col + 1);
            rows.get(row).set(col, cell);
        }

        Canvas copy() {
            List<List<Cell>> copy = new ArrayList<>(rows.size());
            for (List<Cell> row : rows) {
                copy.add(new ArrayList<>(row));
            }
            return new Canvas(copy);
        }
    }

    record Cell(char ch, String fg, String bg, boolean bold) {
        static Cell blank() {
            return new Cell(' ', null, null, false);
        }
    }

    private record Style(String fg, String bg, boolean bold) {
    }
}
