package io.aeyer.voidcore.ws.protocol;

import io.aeyer.voidcore.ws.flow.layout.Element;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;

import java.util.List;
import java.util.Map;

/**
 * Sealed vocabulary of every server-to-client message in v1, per SPEC §4.3.
 * Variants are nested records, mirroring {@link ClientMessage}.
 *
 * <p>Outbound serialization uses {@link ProtocolTypeRegistry} for the
 * class -> wire type-name lookup, then writes the envelope manually. The
 * Jackson polymorphism config here is kept as the single source of truth
 * for the mapping (consumed by the registry); it isn't used for direct
 * Jackson serialization in v1.
 *
 * <p>Cell content format (Row + Span) is per SPEC §4.4. Spec colour names:
 * {@code black|red|green|yellow|blue|magenta|cyan|white|bright_*|default}.
 */
@JsonSubTypes({
        @JsonSubTypes.Type(value = ServerMessage.ScreenDefine.class,       name = "screen.define"),
        @JsonSubTypes.Type(value = ServerMessage.RegionUpdate.class,       name = "region.update"),
        @JsonSubTypes.Type(value = ServerMessage.RegionAppend.class,       name = "region.append"),
        @JsonSubTypes.Type(value = ServerMessage.RegionScrollback.class,   name = "region.scrollback"),
        @JsonSubTypes.Type(value = ServerMessage.RegionClear.class,        name = "region.clear"),
        @JsonSubTypes.Type(value = ServerMessage.RegionNotify.class,       name = "region.notify"),
        @JsonSubTypes.Type(value = ServerMessage.InputPrompt.class,        name = "input.prompt"),
        @JsonSubTypes.Type(value = ServerMessage.InputCancel.class,        name = "input.cancel"),
        @JsonSubTypes.Type(value = ServerMessage.EffectOpenUrl.class,      name = "effect.open_url"),
        @JsonSubTypes.Type(value = ServerMessage.EffectPlaySound.class,    name = "effect.play_sound"),
        @JsonSubTypes.Type(value = ServerMessage.EffectSetTitle.class,     name = "effect.set_title"),
        @JsonSubTypes.Type(value = ServerMessage.EffectCopyClipboard.class, name = "effect.copy_clipboard"),
        @JsonSubTypes.Type(value = ServerMessage.EffectSetTheme.class,      name = "effect.set_theme"),
        @JsonSubTypes.Type(value = ServerMessage.AuthOk.class,             name = "auth.ok"),
        @JsonSubTypes.Type(value = ServerMessage.AuthErr.class,            name = "auth.err"),
        @JsonSubTypes.Type(value = ServerMessage.ResumeOk.class,           name = "resume.ok"),
        @JsonSubTypes.Type(value = ServerMessage.ResumeErr.class,          name = "resume.err"),
        @JsonSubTypes.Type(value = ServerMessage.SystemHeartbeat.class,    name = "system.heartbeat"),
        @JsonSubTypes.Type(value = ServerMessage.ProtocolError.class,      name = "error")
})
@JsonInclude(JsonInclude.Include.NON_NULL)
public sealed interface ServerMessage {

    // --- Cell content (SPEC §4.4) ---------------------------------------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Span(String text, String fg, String bg, Boolean bold) {}

    record Row(int row, List<Span> spans) {}

    record Cursor(int row, int col) {}

    record Layout(String name) {}  // v1 always emits "default" per SPEC §4.3

    // --- Screen / region updates ----------------------------------------

    record ScreenDefine(
            String id,
            int version,
            Layout layout,
            Boolean cacheable,
            Long ttl_seconds
    ) implements ServerMessage {}

    record RegionUpdate(
            String region,
            int version,
            List<Row> content,
            Cursor cursor,
            String mode,    // "fixed" (default; null treated as "fixed") | "flow" — per ADR-031 / SPEC-layout.md §4
            Element tree,
            String focus
    ) implements ServerMessage {

        /** rows path (existing 4-arg). mode/tree/focus default to null. */
        public RegionUpdate(String region, int version, List<Row> content, Cursor cursor) {
            this(region, version, content, cursor, null, null, null);
        }

        /** rows path with explicit mode (existing 5-arg). tree/focus default to null. */
        public RegionUpdate(String region, int version, List<Row> content,
                            Cursor cursor, String mode) {
            this(region, version, content, cursor, mode, null, null);
        }

        /** tree path. content/cursor/mode are null. */
        public static RegionUpdate ofTree(String region, int version,
                                          Element tree,
                                          String focus) {
            return new RegionUpdate(region, version, null, null, null, tree, focus);
        }
    }

    record RegionAppend(
            String region,
            int version,
            List<Row> content
    ) implements ServerMessage {}

    record RegionScrollback(
            String region,
            long before_seq,
            List<Row> content
    ) implements ServerMessage {}

    record RegionClear(String region) implements ServerMessage {}

    record RegionNotify(
            String region,
            List<Row> content,
            Long duration_ms,
            String level   // info | warn | alert
    ) implements ServerMessage {}

    // --- Input mode -----------------------------------------------------

    record InputPrompt(
            String mode,        // none | keystroke | line | password
            String label,
            Integer max_length,
            String valid_keys,
            String initial
    ) implements ServerMessage {}

    record InputCancel() implements ServerMessage {}

    // --- Side effects (SPEC §4.3 closed set) ----------------------------

    record EffectOpenUrl(String url) implements ServerMessage {}

    record EffectPlaySound(String name) implements ServerMessage {}

    record EffectSetTitle(String title) implements ServerMessage {}

    record EffectCopyClipboard(String text) implements ServerMessage {}

    /**
     * Switch the client's CSS theme. {@code name} is one of the canonical
     * theme keys (phosphor, amber, cga, modern). The client toggles a
     * CSS class on the document root so the @media-aware variable set
     * applies without a reload.
     */
    record EffectSetTheme(String name) implements ServerMessage {}

    // --- Auth / resume --------------------------------------------------

    record UserSummary(long id, String handle, boolean is_sysop) {}

    record AuthOk(UserSummary user, String intent_resolved) implements ServerMessage {}

    record AuthErr(String code, String message, String field) implements ServerMessage {}

    /**
     * sync=true: region versions matched, no frames; client keeps painted state.
     * sync=false: region versions diverged; {@code frames} brings client up to date.
     */
    record ResumeOk(boolean sync, List<ServerMessage> frames) implements ServerMessage {}

    record ResumeErr(String code, String message) implements ServerMessage {}

    /**
     * Application-level heartbeat per SPEC §4.1 supplement. Sent by
     * {@link io.aeyer.voidcore.ws.HeartbeatScheduler} every tick alongside
     * the WebSocket protocol-level Ping frame.
     *
     * <p>Why both? Protocol-level pings keep the server's
     * missed-pong counter accurate (browser auto-pongs them) so the
     * server detects half-open WS within ~20s. But control frames
     * (Ping/Pong) don't fire {@code onmessage} in the browser, so the
     * client can't detect a half-open inbound channel from those alone.
     * This data frame fires {@code onmessage} on the client; the
     * client's watchdog in {@code ws.ts} resets its
     * "last-server-activity" timestamp on every received frame
     * (heartbeat or otherwise) and forces a reconnect when no frame
     * has arrived in &gt; 25 seconds.
     *
     * <p>Empty payload — purely a liveness signal.
     */
    record SystemHeartbeat() implements ServerMessage {}

    // --- Protocol-level error (SPEC §4.7) -------------------------------

    record ProtocolError(
            String code,
            String message,
            String ref_id,
            // Optional bag for codes that carry extra info (e.g. RATE_LIMITED.retry_after_ms)
            Map<String, Object> details
    ) implements ServerMessage {}
}
