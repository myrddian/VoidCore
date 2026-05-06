package io.aeyer.voidcore.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@Service
public class LlmGatewayService {

    private static final Logger log = LoggerFactory.getLogger(LlmGatewayService.class);
    private static final MediaType JSON = MediaType.get("application/json");

    private final LlmGatewayProperties props;
    private final ObjectMapper mapper;
    private final OkHttpClient blockingHttp;
    private final OkHttpClient streamingHttp;

    public LlmGatewayService(LlmGatewayProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.blockingHttp = httpClient(props.getTimeouts().getBlockingSeconds());
        this.streamingHttp = httpClient(props.getTimeouts().getStreamingSeconds());
    }

    public boolean isEnabled() {
        return props.isConfigured();
    }

    public ChatCompletion complete(List<ChatMessage> messages, double temperature) {
        requireEnabled();
        log.info("llm-chat-start model={} baseUrl={} messages={} temperature={}",
                props.getChatModel(), props.getBaseUrl(), messages == null ? 0 : messages.size(), temperature);
        Request request = withAuth(new Request.Builder()
                .url(props.getBaseUrl() + "/chat/completions")
                .post(RequestBody.create(toJson(chatRequestBody(messages, temperature, false)), JSON)))
                .build();
        String responseBody = executeWithRetry(blockingHttp, request, "chat");
        try {
            ChatCompletion completion = parseChatCompletion(mapper.readTree(responseBody));
            log.info("llm-chat-success model={} finishReason={} usage={}",
                    props.getChatModel(), completion.finishReason(), completion.usage());
            return completion;
        } catch (IOException e) {
            log.warn("llm-chat-parse-failure model={} reason={}", props.getChatModel(), e.getMessage());
            throw new LlmGatewayException("Failed to parse chat response JSON", e);
        }
    }

    public CompletableFuture<ChatCompletion> completeStreaming(List<ChatMessage> messages,
                                                               double temperature,
                                                               Consumer<String> tokenHandler) {
        requireEnabled();
        log.info("llm-stream-start model={} baseUrl={} messages={} temperature={}",
                props.getChatModel(), props.getBaseUrl(), messages == null ? 0 : messages.size(), temperature);
        Request request = withAuth(new Request.Builder()
                .url(props.getBaseUrl() + "/chat/completions")
                .post(RequestBody.create(toJson(chatRequestBody(messages, temperature, true)), JSON))
                .header("Accept", "text/event-stream"))
                .build();

        CompletableFuture<ChatCompletion> future = new CompletableFuture<>();
        StringBuilder accumulator = new StringBuilder();
        AtomicReference<String> finishReason = new AtomicReference<>();
        AtomicReference<TokenUsage> usage = new AtomicReference<>();

        EventSource.Factory factory = EventSources.createFactory(streamingHttp);
        EventSource source = factory.newEventSource(request, new EventSourceListener() {
            @Override
            public void onEvent(EventSource src, String id, String type, String data) {
                if ("[DONE]".equals(data)) {
                    src.cancel();
                    future.complete(new ChatCompletion(accumulator.toString(), finishReason.get(), usage.get()));
                    return;
                }
                try {
                    JsonNode chunk = mapper.readTree(data);
                    JsonNode choice = chunk.path("choices").path(0);
                    String delta = choice.path("delta").path("content").asText("");
                    if (!delta.isEmpty()) {
                        accumulator.append(delta);
                        tokenHandler.accept(delta);
                    }
                    String fr = choice.path("finish_reason").asText(null);
                    if (fr != null && !"null".equals(fr)) finishReason.set(fr);
                    JsonNode usageNode = chunk.path("usage");
                    if (!usageNode.isMissingNode() && !usageNode.isNull()) {
                        usage.set(parseUsage(usageNode));
                    }
                } catch (IOException e) {
                    src.cancel();
                    future.completeExceptionally(new LlmGatewayException("Failed to parse SSE chunk", e));
                }
            }

            @Override
            public void onClosed(EventSource src) {
                if (!future.isDone()) {
                    log.info("llm-stream-success model={} finishReason={} usage={}",
                            props.getChatModel(), finishReason.get(), usage.get());
                    future.complete(new ChatCompletion(accumulator.toString(), finishReason.get(), usage.get()));
                }
            }

            @Override
            public void onFailure(EventSource src, Throwable t, Response response) {
                String detail = response == null
                        ? (t == null ? "unknown failure" : t.getMessage())
                        : "HTTP " + response.code();
                if (response != null) response.close();
                log.warn("llm-stream-failure model={} reason={}", props.getChatModel(), detail);
                future.completeExceptionally(new LlmGatewayException("Streaming chat failed: " + detail, t));
            }
        });
        future.whenComplete((r, e) -> source.cancel());
        return future;
    }

    private OkHttpClient httpClient(int readSeconds) {
        return new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .writeTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(readSeconds))
                .retryOnConnectionFailure(false)
                .build();
    }

    private void requireEnabled() {
        if (!isEnabled()) {
            throw new LlmGatewayException("LLM gateway is not configured");
        }
    }

    private String executeWithRetry(OkHttpClient http, Request request, String label) {
        int maxAttempts = Math.max(1, props.getRetry().getMaxAttempts());
        long backoff = props.getRetry().getInitialBackoffMs();
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try (Response response = http.newCall(request).execute()) {
                String responseBody = readBody(response);
                if (!response.isSuccessful()) {
                    throw new LlmGatewayException("LLM gateway " + label + " failed: HTTP " + response.code());
                }
                return responseBody;
            } catch (IOException e) {
                lastFailure = e;
                log.warn("llm-{}-retry attempt={} maxAttempts={} reason={}",
                        label, attempt, maxAttempts, e.getMessage());
                if (attempt < maxAttempts) {
                    http.connectionPool().evictAll();
                    try {
                        Thread.sleep(backoff);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new LlmGatewayException("Interrupted during retry backoff", ie);
                    }
                    backoff *= 2;
                }
            }
        }
        throw new LlmGatewayException(
                "LLM gateway " + label + " failed after " + maxAttempts + " attempts: "
                        + (lastFailure == null ? "unknown failure" : lastFailure.getMessage()),
                lastFailure);
    }

    private Map<String, Object> chatRequestBody(List<ChatMessage> messages,
                                                double temperature,
                                                boolean stream) {
        List<Map<String, String>> payloadMessages = new ArrayList<>();
        for (ChatMessage message : messages) {
            if (message == null) continue;
            String role = message.role() == null || message.role().isBlank() ? "user" : message.role();
            payloadMessages.add(Map.of(
                    "role", role,
                    "content", message.content() == null ? "" : message.content()));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", props.getChatModel());
        body.put("messages", payloadMessages);
        body.put("temperature", temperature);
        if (stream) body.put("stream", true);
        return body;
    }

    private ChatCompletion parseChatCompletion(JsonNode root) {
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new LlmGatewayException("Chat response had no choices");
        }
        JsonNode choice = choices.get(0);
        String content = choice.path("message").path("content").asText("");
        String finish = choice.path("finish_reason").asText(null);
        TokenUsage usage = null;
        JsonNode usageNode = root.path("usage");
        if (!usageNode.isMissingNode() && !usageNode.isNull()) {
            usage = parseUsage(usageNode);
        }
        return new ChatCompletion(content, finish, usage);
    }

    private TokenUsage parseUsage(JsonNode usageNode) {
        return new TokenUsage(
                usageNode.path("prompt_tokens").isInt() ? usageNode.path("prompt_tokens").asInt() : null,
                usageNode.path("completion_tokens").isInt() ? usageNode.path("completion_tokens").asInt() : null,
                usageNode.path("total_tokens").isInt() ? usageNode.path("total_tokens").asInt() : null);
    }

    private Request.Builder withAuth(Request.Builder builder) {
        if (props.hasApiKey()) {
            builder.header("Authorization", "Bearer " + props.getApiKey());
        }
        return builder;
    }

    private byte[] toJson(Object value) {
        try {
            return mapper.writeValueAsBytes(value);
        } catch (IOException e) {
            throw new LlmGatewayException("Failed to encode request body", e);
        }
    }

    private String readBody(Response response) throws IOException {
        ResponseBody body = response.body();
        return body == null ? "" : body.string();
    }

    @PreDestroy
    void shutdown() {
        for (OkHttpClient client : List.of(blockingHttp, streamingHttp)) {
            client.dispatcher().executorService().shutdown();
            client.connectionPool().evictAll();
        }
    }
}
