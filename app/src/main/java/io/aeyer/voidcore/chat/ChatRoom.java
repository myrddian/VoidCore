package io.aeyer.voidcore.chat;

/**
 * Visible chat room metadata for room selection and access checks.
 */
public record ChatRoom(
        long id,
        String slug,
        String label,
        boolean privateRoom,
        boolean directMessageRoom,
        boolean active,
        int sortOrder
) {
    public ChatRoom(long id,
                    String slug,
                    String label,
                    boolean privateRoom,
                    boolean active,
                    int sortOrder) {
        this(id, slug, label, privateRoom, false, active, sortOrder);
    }
}
