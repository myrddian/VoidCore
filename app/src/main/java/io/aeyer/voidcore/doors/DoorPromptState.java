package io.aeyer.voidcore.doors;

public record DoorPromptState(
        String mode,
        String label,
        Integer maxLength,
        String validKeys
) {
    public static DoorPromptState none() {
        return new DoorPromptState("none", null, null, null);
    }
}
