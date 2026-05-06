package io.aeyer.voidcore.presence;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory registry of authenticated users currently connected. Drives
 * the "who's online" screen, the {@code *** HANDLE has logged on, node NN ***}
 * banner, and node-number assignment per SPEC §8.1 / §10 Phase 1.
 *
 * <p>Single-process state — multi-node clustering is non-goal per SPEC §11.
 * If clustering ever lands, the same interface ports to a Redis-backed
 * implementation.
 */
@Component
public class PresenceService {

    private final ConcurrentHashMap<String, PresenceEntry> bySession = new ConcurrentHashMap<>();
    private final AtomicInteger nextNode = new AtomicInteger(0);
    private final Clock clock;

    public PresenceService(Clock clock) {
        this.clock = clock;
    }

    /** Register a freshly-authenticated session. Returns the assigned entry. */
    public PresenceEntry register(String sessionId, long userId, String handle, boolean isSysop) {
        int node = nextNode.incrementAndGet();
        PresenceEntry e = new PresenceEntry(
                sessionId, userId, handle, isSysop, node, Instant.now(clock));
        bySession.put(sessionId, e);
        return e;
    }

    /** Remove a session on disconnect. Returns the entry that was there, if any. */
    public Optional<PresenceEntry> unregister(String sessionId) {
        return Optional.ofNullable(bySession.remove(sessionId));
    }

    public Optional<PresenceEntry> get(String sessionId) {
        return Optional.ofNullable(bySession.get(sessionId));
    }

    /** Live snapshot, sorted by node number. */
    public List<PresenceEntry> activeNow() {
        return bySession.values().stream()
                .sorted(Comparator.comparingInt(PresenceEntry::nodeNumber))
                .toList();
    }

    public int activeCount() {
        return bySession.size();
    }

    public Collection<PresenceEntry> all() {
        return Collections.unmodifiableCollection(bySession.values());
    }
}
