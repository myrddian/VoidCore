package io.aeyer.voidcore.ws.flow.view;

import io.aeyer.voidcore.chat.ChatMessage;
import io.aeyer.voidcore.chat.ChatRepository;
import io.aeyer.voidcore.ws.flow.bus.InProcessMessageBus;
import io.aeyer.voidcore.ws.flow.bus.MessageBus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Mirrors the same ADR-029 cache contract as the other view tests:
 * validates
 * the ADR-029 contract for {@link ChatView}.
 */
class ChatViewTest {

    private ChatRepository repo;
    private MessageBus bus;
    private ChatView view;

    private static final List<ChatMessage> ROWS = List.of(
            new ChatMessage(1, "alice", "hi", ChatRepository.KIND_MSG, OffsetDateTime.now()),
            new ChatMessage(2, "bob", "waves", ChatRepository.KIND_ACTION, OffsetDateTime.now()));

    @BeforeEach
    void setUp() {
        repo = mock(ChatRepository.class);
        when(repo.recent("general", 50)).thenReturn(ROWS);
        when(repo.recent("meta", 50)).thenReturn(List.of(
                new ChatMessage(3, "sysop", "test", ChatRepository.KIND_SYSTEM, OffsetDateTime.now())));
        bus = new InProcessMessageBus();
        view = new ChatView(repo, bus);
        view.subscribe();
    }

    @Test
    void firstReadHitsRepo() {
        assertThat(view.isCached("general")).isFalse();
        assertThat(view.recent("general")).isEqualTo(ROWS);
        assertThat(view.isCached("general")).isTrue();
        verify(repo, times(1)).recent("general", 50);
    }

    @Test
    void subsequentReadsHitCache() {
        view.recent("general");
        view.recent("general");
        view.recent("general");
        verify(repo, times(1)).recent("general", 50);
    }

    @Test
    void busNotifyDropsCache() {
        view.recent("general");
        bus.notify(ChatView.topicFor("general"));
        assertThat(view.isCached("general")).isFalse();
    }

    @Test
    void readAfterNotifyRepopulates() {
        view.recent("general");
        bus.notify(ChatView.topicFor("general"));
        view.recent("general");
        verify(repo, times(2)).recent(eq("general"), eq(50));
    }

    @Test
    void notifyOnUnrelatedTopicDoesNotInvalidate() {
        view.recent("general");
        bus.notify("oneliners");
        bus.notify("bulletins");
        bus.notify("files.releases");
        assertThat(view.isCached("general")).isTrue();
    }

    @Test
    void unsubscribeStopsListening() {
        view.unsubscribe();
        view.recent("general");
        bus.notify(ChatView.topicFor("general"));
        assertThat(view.isCached("general")).isTrue();
    }

    @Test
    void roomCachesAreIndependent() {
        view.recent("general");
        view.recent("meta");
        bus.notify(ChatView.topicFor("general"));
        assertThat(view.isCached("general")).isFalse();
        assertThat(view.isCached("meta")).isTrue();
    }
}
