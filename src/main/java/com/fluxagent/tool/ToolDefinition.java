package com.fluxagent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.Objects;
import java.util.function.BiFunction;

/** Definition of a local tool; cached results should be immutable. */
public record ToolDefinition<T>(String name, String description, JsonNode inputSchema,
                                Duration timeout, Duration cacheTtl, int failureThreshold,
                                Duration recovery, Handler<T> handler, BiFunction<JsonNode, String, T> fallback) {
    public ToolDefinition {
        if (name == null || name.isBlank()) { throw new IllegalArgumentException("Tool name is required"); }
        Objects.requireNonNull(inputSchema, "inputSchema");
        Objects.requireNonNull(handler, "handler");
        if (timeout == null || timeout.isNegative() || timeout.isZero()
                || cacheTtl == null || cacheTtl.isNegative() || failureThreshold < 1
                || recovery == null || recovery.isNegative() || recovery.isZero()) {
            throw new IllegalArgumentException("Invalid tool timeout, TTL or circuit breaker configuration");
        }
        inputSchema = inputSchema.deepCopy();
    }

    @Override
    public JsonNode inputSchema() { return inputSchema.deepCopy(); }

    @FunctionalInterface
    public interface Handler<T> {
        T execute(JsonNode parameters) throws Exception;
    }
}
