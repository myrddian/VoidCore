package io.aeyer.voidcore.ws.flow.view;

import io.aeyer.voidcore.chat.ChatMessage;
import io.aeyer.voidcore.chat.ChatRepository;
import io.aeyer.voidcore.ws.flow.bus.MessageBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Cached read-side view of recent chat messages per ADR-029. Sits
 * between {@link ChatRepository} and {@link io.aeyer.voidcore.ws.flow.screen.Screen}
 * consumers; cache is a by-product of the message bus, not screen
 * state.
 *
 * <p>{@link #recent(String)} returns the most-recent {@value #HISTORY}
 * messages for one room, oldest-first (chat reads top-to-bottom in
 * time order). Mutations (chat insert) stay on {@link ChatRepository};
 * callers publish {@link #topicFor(String)} which drops just that
 * room's cache.
 */
@Component
@ConditionalOnBean(ChatRepository.class)
public class ChatView {

    private static final Logger log = LoggerFactory.getLogger(ChatView.class);

    /** History depth — same window the legacy {@code showChat} used. */
    private static final int HISTORY = 50;

    /** Bus topic prefix this view watches. Writers publish one per room. */
    public static final String TOPIC_PREFIX = "chat:";
    public static final String ROOMS_TOPIC = "chat:rooms";

    private final ChatRepository repo;
    private final MessageBus bus;

    private final ConcurrentMap<String, List<ChatMessage>> cachedByRoom = new ConcurrentHashMap<>();
    private final Set<String> subscribedRooms = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    public ChatView(ChatRepository repo, MessageBus bus) {
        this.repo = repo;
        this.bus = bus;
    }

    @PostConstruct
    void subscribe() { /* lazy per-room subscriptions */ }

    @PreDestroy
    void unsubscribe() {
        closed = true;
        subscribedRooms.stream()
                .map(ChatView::subscriptionKeyFor)
                .forEach(bus::unsubscribeAll);
        subscribedRooms.clear();
        cachedByRoom.clear();
    }

    public static String topicFor(String roomSlug) {
        return TOPIC_PREFIX + roomSlug;
    }

    /** Recent chat messages for one room, oldest-first. */
    public List<ChatMessage> recent(String roomSlug) {
        ensureSubscribed(roomSlug);
        return cachedByRoom.computeIfAbsent(roomSlug, slug -> repo.recent(slug, HISTORY));
    }

    /** Drop the cache for one room. Called by the bus on topic notify. */
    void invalidate(String roomSlug) {
        cachedByRoom.remove(roomSlug);
    }

    /** Visible-for-testing: is the cache currently populated for this room? */
    boolean isCached(String roomSlug) {
        return cachedByRoom.containsKey(roomSlug);
    }

    private void ensureSubscribed(String roomSlug) {
        if (closed) return;
        if (!subscribedRooms.add(roomSlug)) return;
        String topic = topicFor(roomSlug);
        String key = subscriptionKeyFor(roomSlug);
        bus.subscribe(key, topic, () -> invalidate(roomSlug));
        log.debug("ChatView subscribed to topic={}", topic);
    }

    private static String subscriptionKeyFor(String roomSlug) {
        return "view:" + topicFor(roomSlug);
    }
}
