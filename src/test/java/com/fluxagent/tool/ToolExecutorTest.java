package com.fluxagent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class ToolExecutorTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final MutableClock clock = new MutableClock();
    private final ToolRegistry registry = new ToolRegistry();
    private final ToolExecutor executor = new ToolExecutor(registry, mapper, clock);

    @AfterEach
    void close() { executor.close(); }

    @Test
    void registersExecutesAndUnregistersTools() {
        var tool = tool("echo", p -> p.get("query").asText(), Duration.ofSeconds(1), Duration.ZERO);
        registry.register(tool);
        assertThat(executor.execute(tool, Map.of("query", "hello")).data()).isEqualTo("hello");
        assertThatThrownBy(() -> registry.register(tool)).isInstanceOf(IllegalArgumentException.class);
        assertThat(registry.unregister("echo")).isTrue();
        assertThatThrownBy(() -> executor.execute(tool, Map.of("query", "hello")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validatesJsonSchemaBeforeInvokingHandler() {
        AtomicInteger calls = new AtomicInteger();
        var tool = tool("echo", p -> calls.incrementAndGet(), Duration.ofSeconds(1), Duration.ZERO);
        registry.register(tool);
        assertThatThrownBy(() -> executor.execute(tool, Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> executor.execute(tool, Map.of("query", 123))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> executor.execute(tool, Map.of("query", "ok", "extra", true)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(calls).hasValue(0);
    }

    @Test
    void cachesUntilTtlExpiresAndSupportsInvalidation() {
        AtomicInteger calls = new AtomicInteger();
        var tool = tool("echo", p -> calls.incrementAndGet(), Duration.ofSeconds(1), Duration.ofSeconds(10));
        registry.register(tool);
        assertThat(executor.execute(tool, Map.of("query", "hello")).data()).isEqualTo(1);
        assertThat(executor.execute(tool, Map.of("query", "hello")).cached()).isTrue();
        clock.advance(Duration.ofSeconds(10));
        assertThat(executor.execute(tool, Map.of("query", "hello")).data()).isEqualTo(2);
        executor.invalidate("echo");
        assertThat(executor.execute(tool, Map.of("query", "hello")).data()).isEqualTo(3);
    }

    @Test
    void fallbackDoesNotHideFailuresOrPolluteCache() {
        AtomicInteger calls = new AtomicInteger();
        var tool = new ToolDefinition<>("broken", "test", mapper.createObjectNode(), Duration.ofSeconds(1),
                Duration.ofSeconds(10), 2, Duration.ofSeconds(30),
                p -> { calls.incrementAndGet(); throw new IllegalStateException("unavailable"); },
                (p, error) -> "fallback");
        registry.register(tool);
        for (int i = 0; i < 3; i++) {
            var result = executor.execute(tool, Map.of());
            assertThat(result.success()).isFalse();
            assertThat(result.data()).isEqualTo("fallback");
            assertThat(result.cached()).isFalse();
        }
        assertThat(calls).hasValue(2);
        assertThat(executor.stats().get("broken")).isInstanceOfSatisfying(Map.class, stats -> {
            assertThat(stats.get("success_rate")).isEqualTo(0.0);
            assertThat(stats.get("circuit_state")).isEqualTo("open");
        });
        clock.advance(Duration.ofSeconds(30));
        executor.execute(tool, Map.of());
        assertThat(calls).hasValue(3);
    }

    @Test
    void timeoutCancelsTheRunningHandler() throws Exception {
        CountDownLatch interrupted = new CountDownLatch(1);
        var tool = tool("slow", p -> {
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException ex) { interrupted.countDown(); throw ex; }
            return "never";
        }, Duration.ofMillis(100), Duration.ZERO);
        registry.register(tool);
        var result = executor.execute(tool, Map.of("query", "hello"));
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("timeout");
        assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void failedFallbackStillReturnsAFailureResult() {
        var tool = new ToolDefinition<>("broken", "test", mapper.createObjectNode(), Duration.ofSeconds(1),
                Duration.ZERO, 2, Duration.ofSeconds(30), p -> { throw new IllegalStateException("primary"); },
                (p, error) -> { throw new IllegalStateException("fallback"); });
        registry.register(tool);
        assertThat(executor.execute(tool, Map.of()).success()).isFalse();
    }

    @Test
    void oldSuccessfulRequestCannotFinishTheRecoveryProbe() throws Exception {
        CountDownLatch oldStarted = new CountDownLatch(1);
        CountDownLatch releaseOld = new CountDownLatch(1);
        CountDownLatch probeStarted = new CountDownLatch(1);
        CountDownLatch releaseProbe = new CountDownLatch(1);
        var definition = new ToolDefinition<String>("concurrent", "test", mapper.createObjectNode(),
                Duration.ofSeconds(5), Duration.ZERO, 1, Duration.ofSeconds(30), parameters -> {
                    switch (parameters.get("query").asText()) {
                        case "old" -> { oldStarted.countDown(); releaseOld.await(); }
                        case "fail" -> throw new IllegalStateException("failure");
                        case "probe" -> { probeStarted.countDown(); releaseProbe.await(); }
                    }
                    return "ok";
                }, null);
        registry.register(definition);
        try (var callers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var old = callers.submit(() -> executor.execute(definition, Map.of("query", "old")));
            try {
                assertThat(oldStarted.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(executor.execute(definition, Map.of("query", "fail")).success()).isFalse();
                clock.advance(Duration.ofSeconds(30));
                var probe = callers.submit(() -> executor.execute(definition, Map.of("query", "probe")));
                assertThat(probeStarted.await(2, TimeUnit.SECONDS)).isTrue();
                releaseOld.countDown();
                assertThat(old.get(2, TimeUnit.SECONDS).success()).isTrue();
                assertThat(executor.execute(definition, Map.of("query", "another")).error()).isEqualTo("circuit open");
                releaseProbe.countDown();
                assertThat(probe.get(2, TimeUnit.SECONDS).success()).isTrue();
                assertThat(executor.execute(definition, Map.of("query", "another")).success()).isTrue();
            } finally {
                releaseOld.countDown();
                releaseProbe.countDown();
            }
        }
    }

    @Test
    void reregisteredToolDoesNotReuseOldCacheOrStats() {
        var first = tool("echo", p -> "old", Duration.ofSeconds(1), Duration.ofMinutes(1));
        registry.register(first);
        executor.execute(first, Map.of("query", "hello"));
        registry.unregister("echo");
        var second = tool("echo", p -> "new", Duration.ofSeconds(1), Duration.ofMinutes(1));
        registry.register(second);
        assertThat(executor.execute(second, Map.of("query", "hello")).data()).isEqualTo("new");
        assertThatThrownBy(() -> executor.execute(first, Map.of("query", "hello")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private <T> ToolDefinition<T> tool(String name, ToolDefinition.Handler<T> handler, Duration timeout, Duration ttl) {
        return new ToolDefinition<>(name, "test", mapper.valueToTree(Map.of(
                "type", "object", "properties", Map.of("query", Map.of("type", "string", "minLength", 1)),
                "required", new String[]{"query"}, "additionalProperties", false)),
                timeout, ttl, 2, Duration.ofSeconds(30), handler, null);
    }

    static class MutableClock extends Clock {
        private volatile Instant current = Instant.parse("2026-01-01T00:00:00Z");
        void advance(Duration duration) { current = current.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return current; }
    }
}
