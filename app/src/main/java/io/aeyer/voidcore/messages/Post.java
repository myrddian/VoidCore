package io.aeyer.voidcore.messages;

import java.time.OffsetDateTime;

public record Post(
        long id,
        long threadId,
        String authorHandle,
        String body,
        OffsetDateTime postedAt,
        OffsetDateTime editedAt,
        boolean deleted
) {
    public boolean edited() {
        return editedAt != null;
    }
}
