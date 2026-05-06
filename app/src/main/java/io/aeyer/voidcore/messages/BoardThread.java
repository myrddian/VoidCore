package io.aeyer.voidcore.messages;

import java.time.OffsetDateTime;

/**
 * One thread within a message base. Named {@code BoardThread} to avoid the
 * collision with {@link java.lang.Thread}; kept in the {@code messages}
 * package alongside the other forum domain types.
 */
public record BoardThread(
        long id,
        long baseId,
        String subject,
        String authorHandle,
        OffsetDateTime createdAt,
        OffsetDateTime lastPostAt,
        int postCount,
        boolean pinned,
        boolean locked
) {}
