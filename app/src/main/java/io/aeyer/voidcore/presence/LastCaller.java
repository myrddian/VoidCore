package io.aeyer.voidcore.presence;

import java.time.OffsetDateTime;

/**
 * Read-side view of the {@code last_callers} table joined with users.
 * The "last callers" screen renders these in reverse-chronological order.
 */
public record LastCaller(String handle, String location, OffsetDateTime at) {
}
