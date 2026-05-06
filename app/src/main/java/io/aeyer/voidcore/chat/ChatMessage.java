package io.aeyer.voidcore.chat;

import java.time.OffsetDateTime;

/**
 * One row in the {@code chat_room_messages} table joined to the author's handle.
 * {@code kind} is one of {@code msg | action | system} per SPEC §3 — the
 * renderer differentiates {@code action} ({@code /me} actions) and
 * {@code system} (sysop break-ins) from regular {@code msg}.
 */
public record ChatMessage(
        long id,
        String handle,
        String body,
        String kind,
        OffsetDateTime postedAt
) {
}
