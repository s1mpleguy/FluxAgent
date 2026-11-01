package com.echomind.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class ToolExecutor implements AutoCloseable {
    private final ToolRegistry registry;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(4, 16, 60, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(128), Thread.ofPlatform().daemon().name("tool-", 0).factory());

    @Autowired
    public ToolExecutor(ToolRegistry registry, ObjectMapper mapper) {
        this(registry, mapper, Clock.systemUTC());
    }

    ToolExecutor(ToolRegistry registry, ObjectMapper mapper, Clock clock) {
        this.registry = registry;
        this.mapper = mapper;
        this.clock = clock;
    }

    public ToolResult<?> execute(String name, Map<String, ?> parameters) {
        return executeRegistration(registry.require(name), parameters);
    }

    @SuppressWarnings("unchecked")
    public <T> ToolResult<T> execute(ToolDefinition<T> definition, Map<String, ?> parameters) {
        var registration = registry.require(definition.name());
        if (registration.definition != definition) {
            throw new IllegalArgumentException("Tool definition is no longer registered: " + definition.name());
        }
        return executeRegistration((ToolRegistry.Registration<T>) registration, parameters);
    }

    private <T> ToolResult<T> executeRegistration(ToolRegistry.Registration<T> registration, Map<String, ?> parameters) {
        JsonNode input = mapper.valueToTree(parameters);
        var errors = registration.schema.validate(input);
        if (!errors.isEmpty()) { throw new IllegalArgumentException("Invalid tool parameters: " + errors); }
        long start = System.nanoTime();
        var definition = registration.definition;
        long generation = registration.generation();
        T cached = registration.cached(input, clock.instant());
        if (cached != null) {
            registration.stats.record(true, elapsed(start));
            return new ToolResult<>(true, cached, definition.name(), null, true, elapsed(start), false);
        }
        var breaker = registration.breaker(clock);
        var permit = breaker.acquire();
        if (permit == null) {
            registration.stats.record(false, elapsed(start));
            return new ToolResult<>(false, fallback(definition, input, "circuit open"), definition.name(),
                    "circuit open", false, elapsed(start), false);
        }
        Future<T> future = null;
        try {
            future = workers.submit(() -> definition.handler().execute(input.deepCopy()));
            T value = Objects.requireNonNull(future.get(definition.timeout().toNanos(), TimeUnit.NANOSECONDS), "Tool returned null");
            registration.cache(input, value, clock.instant(), generation);
            registration.stats.record(true, elapsed(start));
            breaker.recordSuccess(permit);
            return new ToolResult<>(true, value, definition.name(), null, false, elapsed(start), false);
        } catch (Exception ex) {
            if (future != null) { future.cancel(true); }
            workers.purge();
            registration.stats.record(false, elapsed(start));
            breaker.recordFailure(permit);
            String error = ex instanceof TimeoutException ? "timeout" : rootMessage(ex);
            if (ex instanceof InterruptedException) { Thread.currentThread().interrupt(); }
            return new ToolResult<>(false, fallback(definition, input, error), definition.name(), error,
                    false, elapsed(start), false);
        }
    }

    private <T> T fallback(ToolDefinition<T> definition, JsonNode input, String error) {
        if (definition.fallback() == null || Thread.currentThread().isInterrupted()) { return null; }
        Future<T> future = null;
        try {
            future = workers.submit(() -> definition.fallback().apply(input.deepCopy(), error));
            return future.get(definition.timeout().toNanos(), TimeUnit.NANOSECONDS);
        } catch (Exception ex) {
            if (future != null) { future.cancel(true); }
            workers.purge();
            if (ex instanceof InterruptedException) { Thread.currentThread().interrupt(); }
            return null;
        }
    }

    public void invalidate(String name) { registry.require(name).invalidate(); }

    public Map<String, Object> stats() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (var registration : registry.registrations()) {
            Map<String, Object> stats = new LinkedHashMap<>(registration.stats.snapshot());
            stats.put("circuit_state", registration.breaker(clock).state().name().toLowerCase(java.util.Locale.ROOT));
            result.put(registration.definition.name(), Map.copyOf(stats));
        }
        return Map.copyOf(result);
    }

    private long elapsed(long start) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start); }

    private String rootMessage(Exception exception) {
        Throwable cause = exception.getCause() == null ? exception : exception.getCause();
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    @Override
    @PreDestroy
    public void close() { workers.shutdownNow(); }
}
