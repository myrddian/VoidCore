package io.aeyer.voidcore.netmail;

import java.time.OffsetDateTime;

/**
 * Read-side view of a {@code netmail} row. {@code fromHandle} / {@code toHandle}
 * come from joins onto the users table so the inbox/read screens render
 * without an extra per-row lookup.
 */
public record NetmailMessage(
        long id,
        long fromId,
        long toId,
        String fromHandle,
        String toHandle,
        String subject,
        String body,
        OffsetDateTime sentAt,
        OffsetDateTime readAt
) {
    public boolean unread() {
        return readAt == null;
    }
}
