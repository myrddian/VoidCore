package io.aeyer.voidcore.llm;

public record ChatCompletion(String content, String finishReason, TokenUsage usage) {}
