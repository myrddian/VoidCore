package io.aeyer.voidcore.llm;

public record TokenUsage(Integer promptTokens, Integer completionTokens, Integer totalTokens) {}
