package io.aeyer.voidcore.ws;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Wire envelope for every WS frame, in both directions. Per SPEC §4.2.
 *
 * <p>{@code seq} and {@code mac} are reserved for v2 per-message authentication
 * (ADR-018) and are accepted/ignored on v1. The dispatcher in {@link MessageDispatcher}
 * decodes {@link #payload} into a typed message variant; this record stays
 * untyped at the transport layer so #14's sealed protocol types can be
 * introduced without disturbing the wire pipe.
 */
public record Envelope(
        String id,
        String type,
        String protocol_version,
        long seq,
        String mac,
        JsonNode payload
) {
}
