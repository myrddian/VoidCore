package io.aeyer.voidcore.doors;

import io.aeyer.voidcore.ws.protocol.ServerMessage.Row;

import java.util.List;

public record DoorSessionState(
        String doorId,
        String doorName,
        String doorSessionId,
        Status status,
        List<Row> rows,
        DoorPromptState prompt,
        String notice,
        int version
) {
    public enum Status {
        CONNECTING,
        ACTIVE,
        DETACHED,
        ERROR
    }

    public static DoorSessionState connecting(String doorId, String doorName, String doorSessionId, List<Row> rows) {
        return new DoorSessionState(doorId, doorName, doorSessionId, Status.CONNECTING, rows,
                DoorPromptState.none(), null, 1);
    }
}
