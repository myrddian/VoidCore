package io.aeyer.voidcore.ws.flow.bus;

/**
 * Topic-based, payload-less, in-process pub/sub bus per ADR-027.
 *
 * <p>The bus delivers a single signal: <em>"the data behind this
 * topic changed."</em> No payload, no replay, no durability. Receivers
 * react by re-reading whatever they cached or rendered. The DB is the
 * source of truth; the bus is a refresh trigger.
 *
 * <p>Subscribers register a {@code key} (a stable string identifying the
 * subscriber) plus a {@link Runnable} handler. The same key may
 * subscribe to multiple topics; {@link #unsubscribeAll(String)} clears
 * every subscription for that key in one call. Sessions use their
 * {@code session.id()} as key; non-session subscribers (e.g. cache
 * views per ADR-029) pick a stable string like {@code "view:bulletins"}.
 *
 * <p>Subscribing the <em>same</em> key to the <em>same</em> topic twice
 * replaces the prior handler — useful for screens whose onEvent closure
 * may be re-bound after a state change without leaking a stale handler.
 *
 * <p>Implementations must be thread-safe; {@link #notify(String)} may
 * be invoked from any thread.
 */
public interface MessageBus {

    /**
     * Subscribe {@code handler} to {@code topic} under {@code key}.
     * Replaces any existing handler the same key had on the same topic.
     */
    void subscribe(String key, String topic, Runnable handler);

    /** Remove just the {@code key}/{@code topic} pair, if present. */
    void unsubscribe(String key, String topic);

    /**
     * Remove every subscription held by {@code key} across every
     * topic. Called on session disconnect, and as the first step of
     * any {@link io.aeyer.voidcore.ws.flow.screen.Navigator} push/pop/replaceTop
     * before the new top's topics are subscribed.
     */
    void unsubscribeAll(String key);

    /**
     * Fire every handler subscribed to {@code topic}. Handlers run on
     * the calling thread; exceptions in one handler are logged and
     * swallowed so they don't break delivery to siblings.
     */
    void notify(String topic);
}
