package io.aeyer.voidcore.oneliners;

import java.time.OffsetDateTime;

/**
 * Read-side view of an {@code oneliners} row joined to the author's handle.
 * The wall renders these in reverse-chronological order (newest first).
 */
public record Oneliner(long id, String handle, String body, OffsetDateTime postedAt) {
}
