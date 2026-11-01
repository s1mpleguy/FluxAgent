package com.echomind.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ToolRegistry {
    private final Map<String, Registration<?>> tools = new ConcurrentHashMap<>();

    public <T> void register(ToolDefinition<T> definition) {
        var schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(definition.inputSchema());
        if (tools.putIfAbsent(definition.name(), new Registration<>(definition, schema)) != null) {
            throw new IllegalArgumentException("Tool already registered: " + definition.name());
        }
    }

    public boolean unregister(String name) { return tools.remove(name) != null; }

    public List<String> names() { return tools.keySet().stream().sorted().toList(); }

    Registration<?> require(String name) {
        Registration<?> registration = tools.get(name);
        if (registration == null) { throw new IllegalArgumentException("Unknown tool: " + name); }
        return registration;
    }

    List<Registration<?>> registrations() { return List.copyOf(tools.values()); }

    static final class Registration<T> {
        final ToolDefinition<T> definition;
        final Schema schema;
        final ToolStats stats = new ToolStats();
        private CircuitBreaker breaker;
        private long generation;
        private final Map<JsonNode, CacheEntry<T>> cache = new LinkedHashMap<>(16, 0.75f, true);

        Registration(ToolDefinition<T> definition, Schema schema) {
            this.definition = definition;
            this.schema = schema;
        }

        synchronized CircuitBreaker breaker(Clock clock) {
            if (breaker == null) {
                breaker = new CircuitBreaker(definition.failureThreshold(), definition.recovery(), clock);
            }
            return breaker;
        }

        synchronized long generation() { return generation; }

        synchronized T cached(JsonNode key, Instant now) {
            cache.values().removeIf(entry -> !now.isBefore(entry.expiresAt()));
            CacheEntry<T> entry = cache.get(key);
            return entry == null ? null : entry.value();
        }

        synchronized void cache(JsonNode key, T value, Instant now, long startedGeneration) {
            if (definition.cacheTtl().isZero() || generation != startedGeneration) { return; }
            cache.values().removeIf(entry -> !now.isBefore(entry.expiresAt()));
            cache.put(key, new CacheEntry<>(value, now.plus(definition.cacheTtl())));
            while (cache.size() > 1024) { cache.remove(cache.keySet().iterator().next()); }
        }

        synchronized void invalidate() {
            generation++;
            cache.clear();
        }
    }

    private record CacheEntry<T>(T value, Instant expiresAt) { }
}
