package io.aeyer.voidcore.auth;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;

/**
 * In-memory view of a row in the {@code sessions} table per SPEC §3 / §5.
 * Records are immutable; the repository returns a fresh instance on every
 * read.
 */
public record Session(
        String token,
        long userId,
        OffsetDateTime createdAt,
        OffsetDateTime lastSeenAt,
        OffsetDateTime expiresAt,
        String ip,
        String userAgent,
        JsonNode currentScreen
) {
}
