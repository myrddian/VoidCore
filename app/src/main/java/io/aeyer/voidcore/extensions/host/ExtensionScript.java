package io.aeyer.voidcore.extensions.host;

/**
 * Runtime-neutral lifecycle for one extension-backed screen instance.
 */
public interface ExtensionScript {

    void onEnter(ExtensionHostContext ctx);

    default void onKey(ExtensionHostContext ctx, String key) {
    }

    default void onLine(ExtensionHostContext ctx, String text) {
    }

    default void onCancel(ExtensionHostContext ctx) {
        ctx.navigation().pop();
    }
}
