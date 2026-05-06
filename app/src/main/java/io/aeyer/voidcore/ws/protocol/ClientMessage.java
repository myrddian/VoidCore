package io.aeyer.voidcore.ws.protocol;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * Sealed vocabulary of every client-to-server message in v1, per SPEC §4.3
 * "Client-to-server messages". Variants are nested records so the closed set
 * is visible at one location. Switch-pattern-match on a {@code ClientMessage}
 * value yields a compile-time totality check — adding a new variant breaks
 * the build until every dispatcher updates its switch.
 *
 * <p>{@link JsonSubTypes} is the single source of truth for type-name
 * mapping; {@link ProtocolTypeRegistry} reads it via reflection so we don't
 * maintain two parallel name tables.
 *
 * <p>Per SPEC §4.2 the wire envelope wraps these payloads. Inbound parsing
 * happens in two passes: the transport layer parses the envelope as raw
 * JSON, then the dispatcher uses the registry to deserialize this payload
 * to the matching nested record and runs jakarta.validation on it.
 */
@JsonSubTypes({
        @JsonSubTypes.Type(value = ClientMessage.AuthLogin.class,    name = "auth.login"),
        @JsonSubTypes.Type(value = ClientMessage.AuthRegister.class, name = "auth.register"),
        @JsonSubTypes.Type(value = ClientMessage.AuthResume.class,   name = "auth.resume"),
        @JsonSubTypes.Type(value = ClientMessage.AuthLogout.class,   name = "auth.logout"),
        @JsonSubTypes.Type(value = ClientMessage.Keystroke.class,    name = "keystroke"),
        @JsonSubTypes.Type(value = ClientMessage.LineSubmit.class,   name = "line.submit"),
        @JsonSubTypes.Type(value = ClientMessage.LineCancel.class,   name = "line.cancel"),
        @JsonSubTypes.Type(value = ClientMessage.ScrollRequest.class, name = "scroll.request"),
        @JsonSubTypes.Type(value = ClientMessage.ViewportResize.class, name = "viewport.resize"),
        @JsonSubTypes.Type(value = ClientMessage.EditorCommit.class,   name = "editor.commit"),
        @JsonSubTypes.Type(value = ClientMessage.EditorCancel.class,   name = "editor.cancel"),
        @JsonSubTypes.Type(value = ClientMessage.EditorSnapshot.class, name = "editor.snapshot"),
        @JsonSubTypes.Type(value = ClientMessage.FieldCommit.class,    name = "field.commit"),
        @JsonSubTypes.Type(value = ClientMessage.FieldCancel.class,    name = "field.cancel"),
        @JsonSubTypes.Type(value = ClientMessage.FocusMove.class,      name = "focus.move")
})
public sealed interface ClientMessage {

    // --- Authentication --------------------------------------------------

    record AuthLogin(
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9_\\-.]{3,16}$") String handle,
            @NotBlank @Size(min = 8, max = 4096) String password,
            String intent
    ) implements ClientMessage {}

    record AuthRegister(
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9_\\-.]{3,16}$") String handle,
            @NotBlank @Size(min = 8, max = 4096) String password,
            @Size(max = 128) String location,
            @Size(max = 256) String setup,
            @Size(max = 256) String found_via,
            @Size(max = 256) String fav_genres
    ) implements ClientMessage {}

    record AuthResume(
            // Nullable: a client with no localStorage token still sends
            // auth.resume on connect to deliver the URL fragment intent
            // (SPEC §4.6). Null token means "no resume, just save the intent
            // until I successfully log in".
            String token,
            String intent,
            // Per SPEC §4.5 — the region->lastSeenVersion map for sync detection.
            // Nullable: a fresh client has nothing to declare.
            Map<String, Long> region_versions
    ) implements ClientMessage {}

    record AuthLogout() implements ClientMessage {}

    // --- Terminal mechanics ---------------------------------------------

    record Keystroke(
            @NotBlank @Size(min = 1, max = 16) String key
    ) implements ClientMessage {}

    record LineSubmit(
            @NotNull @Size(max = 4096) String text
    ) implements ClientMessage {}

    record LineCancel() implements ClientMessage {}

    record ScrollRequest(
            @NotBlank String region,
            @NotBlank @Pattern(regexp = "^(up|down)$") String direction,
            @Min(1) @Max(1000) int amount
    ) implements ClientMessage {}

    record ViewportResize(
            @Min(20) @Max(500) int cols,
            @Min(10) @Max(500) int rows
    ) implements ClientMessage {}

    // --- Rich editor widget mechanics -----------------------------------

    record EditorCommit(
            String widget_id,
            String content,
            String action
    ) implements ClientMessage {}

    record EditorCancel(
            String widget_id,
            boolean force
    ) implements ClientMessage {}

    record EditorSnapshot(
            String widget_id,
            String content
    ) implements ClientMessage {}

    // --- Form field widget mechanics -----------------------------------

    record FieldCommit(
            String widget_id,
            String value
    ) implements ClientMessage {}

    record FieldCancel(
            String widget_id
    ) implements ClientMessage {}

    // --- Focus management -----------------------------------------------

    record FocusMove(
            String from,
            String direction
    ) implements ClientMessage {}
}
