package io.aeyer.voidcore.ws.flow;

import io.aeyer.voidcore.branding.BrandingProperties;
import io.aeyer.voidcore.ws.protocol.ServerMessage.Row;
import io.aeyer.voidcore.ws.protocol.ServerMessage.Span;

import java.util.ArrayList;
import java.util.List;

/**
 * Runtime-rendered branding banner. The startup banner comes from
 * {@code src/main/resources/banner.txt}; this class renders the
 * in-app login/menu banner from the same env-backed branding values
 * so startup and runtime branding stay aligned.
 */
public final class Banner {

    private static final int BOX_INNER_WIDTH = 64;

    private static final String[] FG_BY_ROW = {
            "default",          // 0 blank
            "bright_magenta",   // 1
            "bright_magenta",   // 2
            "magenta",          // 3
            "magenta",          // 4
            "default",          // 5 blank
            "bright_cyan",      // 6 top border
            "bright_cyan",      // 7 inner row 1
            "bright_cyan",      // 8 inner row 2
            "bright_cyan",      // 9 bottom border
            "default",          // 10 blank
            "default"           // 11 blank
    };
    private static volatile Branding branding = Branding.defaults();

    private Banner() {}

    public static void configure(BrandingProperties props) {
        branding = Branding.from(props);
    }

    /**
     * A compact 1-line banner for use when a {@code ScreenApp} is on top.
     * Frees vertical space for the editor body while still providing a
     * breadcrumb via {@code contextLabel} (e.g. {@code "DOCUMENT · my-slug"}).
     *
     * <p>Visual separation between banner and content comes from CSS on
     * the banner region (border-bottom on {@code [data-region="banner"]}),
     * not from a literal underline row — that wrapped on viewports
     * narrower than 80 chars.
     */
    public static List<Row> minimalRows(String contextLabel) {
        String label = contextLabel == null ? "" : contextLabel;
        String text = label.isBlank() ? branding.name() : branding.name() + " · " + label;
        return List.of(
            new Row(0, List.of(new Span(text, "bright_magenta", null, null)))
        );
    }

    public static List<Row> rows() {
        List<String> lines = renderLines();
        List<Row> out = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            String fg = i < FG_BY_ROW.length ? FG_BY_ROW[i] : "default";
            out.add(new Row(i, List.of(new Span(lines.get(i), fg, null, null))));
        }
        return out;
    }

    private static List<String> renderLines() {
        Branding current = branding;
        String version = applicationVersion();
        String headline = current.description().isBlank()
                ? current.name()
                : current.name() + "  -  " + current.description();
        List<String> details = new ArrayList<>();
        if (!current.fidoAddr().isBlank()) details.add("net " + current.fidoAddr());
        details.add("sysop " + current.sysopHandle());
        details.add("running VOIDcore/" + version);
        return List.of(
                "",
                indent(spaced(current.name())),
                indent(repeat('-', Math.max(3, spaced(current.name()).length()))),
                indent(current.tagline()),
                indent(current.subtagline()),
                "",
                boxBorder(),
                boxLine(headline),
                boxLine(String.join("  -  ", details)),
                boxBorder(),
                "",
                ""
        );
    }

    private static String applicationVersion() {
        String version = Banner.class.getPackage().getImplementationVersion();
        return version == null || version.isBlank() ? "dev" : version;
    }

    private static String spaced(String value) {
        StringBuilder out = new StringBuilder();
        String upper = value.toUpperCase();
        for (int i = 0; i < upper.length(); i++) {
            if (i > 0) out.append(' ');
            out.append(upper.charAt(i));
        }
        return out.toString();
    }

    private static String indent(String value) {
        return "         " + value;
    }

    private static String boxBorder() {
        return "+----------------------------------------------------------------+";
    }

    private static String boxLine(String value) {
        String content = fit(value, BOX_INNER_WIDTH);
        return "|" + content + "|";
    }

    private static String fit(String value, int width) {
        String text = value == null ? "" : value;
        if (text.length() > width) return text.substring(0, width);
        return text + " ".repeat(width - text.length());
    }

    private static String repeat(char c, int count) {
        return String.valueOf(c).repeat(Math.max(0, count));
    }

    static void resetForTests() {
        branding = Branding.defaults();
    }

    private record Branding(String name,
                            String tagline,
                            String description,
                            String subtagline,
                            String sysopHandle,
                            String fidoAddr) {
        private static Branding defaults() {
            return new Branding("VOIDcore", "typed-document terminal platform",
                    "a self-hosted typed-document community platform", "", "sysop", "23:495/0");
        }

        private static Branding from(BrandingProperties props) {
            return new Branding(
                    props.displayName(),
                    props.displayTagline(),
                    props.displayDescription(),
                    props.displaySubtagline(),
                    props.displaySysopHandle(),
                    props.displayFidoAddr());
        }
    }
}
