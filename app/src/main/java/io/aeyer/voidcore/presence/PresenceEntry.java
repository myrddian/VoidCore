package io.aeyer.voidcore.presence;

import java.time.Instant;

/**
 * One authenticated, currently-connected user as seen by
 * {@link PresenceService}. Held in memory only; cleared on disconnect.
 */
public record PresenceEntry(
        String sessionId,
        long userId,
        String handle,
        boolean isSysop,
        int nodeNumber,
        Instant joinedAt
) {
}
