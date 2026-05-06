package io.aeyer.voidcore.doors;

public record DoorSummary(
        String doorId,
        String name,
        String description,
        int attachedSessions
) {
}
