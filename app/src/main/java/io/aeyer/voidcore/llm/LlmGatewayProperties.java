package io.aeyer.voidcore.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "voidcore.llm")
public class LlmGatewayProperties {

    private boolean enabled = false;
    private String baseUrl = "";
    private String chatModel = "";
    private String apiKey = "";
    private Timeouts timeouts = new Timeouts();
    private Retry retry = new Retry();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl == null ? "" : baseUrl; }

    public String getChatModel() { return chatModel; }
    public void setChatModel(String chatModel) { this.chatModel = chatModel == null ? "" : chatModel; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey == null ? "" : apiKey; }

    public boolean hasApiKey() { return apiKey != null && !apiKey.isBlank(); }

    public boolean isConfigured() {
        return enabled && baseUrl != null && !baseUrl.isBlank()
                && chatModel != null && !chatModel.isBlank();
    }

    public Timeouts getTimeouts() { return timeouts; }
    public void setTimeouts(Timeouts timeouts) { this.timeouts = timeouts; }

    public Retry getRetry() { return retry; }
    public void setRetry(Retry retry) { this.retry = retry; }

    public static class Timeouts {
        private int blockingSeconds = 60;
        private int streamingSeconds = 90;

        public int getBlockingSeconds() { return blockingSeconds; }
        public void setBlockingSeconds(int blockingSeconds) { this.blockingSeconds = blockingSeconds; }

        public int getStreamingSeconds() { return streamingSeconds; }
        public void setStreamingSeconds(int streamingSeconds) { this.streamingSeconds = streamingSeconds; }
    }

    public static class Retry {
        private int maxAttempts = 2;
        private long initialBackoffMs = 500;

        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }

        public long getInitialBackoffMs() { return initialBackoffMs; }
        public void setInitialBackoffMs(long initialBackoffMs) { this.initialBackoffMs = initialBackoffMs; }
    }
}
