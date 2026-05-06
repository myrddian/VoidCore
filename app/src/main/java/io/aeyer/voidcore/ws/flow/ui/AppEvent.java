package io.aeyer.voidcore.ws.flow.ui;

/**
 * Sealed union of app-level events the framework dispatches to a
 * {@link io.aeyer.voidcore.ws.flow.screen.ScreenApp#onEvent}.
 *
 * <p>Wire-level {@link io.aeyer.voidcore.ws.protocol.ClientMessage} types
 * map onto these in {@code ScreenRouter}. Internal-only — the wire
 * vocabulary stays in ClientMessage.
 */
public sealed interface AppEvent {

    record FieldCommit(String widgetId, String value) implements AppEvent {}

    record FieldCancel(String widgetId) implements AppEvent {}

    record EditorCommit(String widgetId, String content, String action) implements AppEvent {}

    record EditorCancel(String widgetId, boolean force) implements AppEvent {}

    record EditorSnapshot(String widgetId, String content) implements AppEvent {}

    record FocusMove(String from, String direction) implements AppEvent {}
}
