package io.aeyer.voidcore.ws.flow.bus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process implementation of {@link MessageBus} per ADR-027.
 *
 * <p>Storage is two maps:
 * <ul>
 *   <li>{@code topicToSubscribers}: {@code topic → key → handler}.
 *       The inner map is the delivery set for a topic; on
 *       {@link #notify(String)} we copy its values and run them.</li>
 *   <li>{@code keyToTopics}: reverse index so
 *       {@link #unsubscribeAll(String)} is O(topics-the-key-holds)
 *       rather than O(every-topic).</li>
 * </ul>
 *
 * <p>Both maps use {@link ConcurrentHashMap} so concurrent
 * subscribe/notify is safe. Notify copies the current handler set
 * before invoking — a handler that re-subscribes (or that triggers
 * another notify) won't ConcurrentModification its sibling delivery.
 *
 * <p>Single-node only. ADR-027 calls out the multi-node path
 * (replace this bean with a Redis / NATS-backed bus); deferred until
 * multi-node deployment is a real concern.
 */
@Component
public class InProcessMessageBus implements MessageBus {

    private static final Logger log = LoggerFactory.getLogger(InProcessMessageBus.class);

    private final Map<String, Map<String, Runnable>> topicToSubscribers = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> keyToTopics = new ConcurrentHashMap<>();

    @Override
    public void subscribe(String key, String topic, Runnable handler) {
        topicToSubscribers
                .computeIfAbsent(topic, t -> new ConcurrentHashMap<>())
                .put(key, handler);
        keyToTopics
                .computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet())
                .add(topic);
    }

    @Override
    public void unsubscribe(String key, String topic) {
        Map<String, Runnable> subs = topicToSubscribers.get(topic);
        if (subs != null) subs.remove(key);
        Set<String> topics = keyToTopics.get(key);
        if (topics != null) topics.remove(topic);
    }

    @Override
    public void unsubscribeAll(String key) {
        Set<String> topics = keyToTopics.remove(key);
        if (topics == null) return;
        for (String t : topics) {
            Map<String, Runnable> subs = topicToSubscribers.get(t);
            if (subs != null) subs.remove(key);
        }
    }

    @Override
    public void notify(String topic) {
        Map<String, Runnable> subs = topicToSubscribers.get(topic);
        if (subs == null || subs.isEmpty()) return;
        // Copy first — a handler that resubscribes or notifies another
        // topic must not concurrent-modify the delivery set.
        List<Runnable> handlers = new ArrayList<>(subs.values());
        for (Runnable h : handlers) {
            try {
                h.run();
            } catch (Exception e) {
                log.warn("topic={} handler failed: {}", topic, e.toString());
            }
        }
    }
}
