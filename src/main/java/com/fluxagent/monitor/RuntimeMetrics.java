package com.fluxagent.monitor;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/** Publishes bounded, per-registration snapshots; removed registrations lose their meters. */
final class RuntimeMetrics {
    private final MeterRegistry registry;
    private final Map<String, Series> agents = new HashMap<>();
    private final Map<String, Series> tools = new HashMap<>();
    private volatile int activeAlerts;
    private volatile int retainedResolvedAlerts;

    RuntimeMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder("fluxagent.alerts.active", this, metrics -> metrics.activeAlerts).register(registry);
        Gauge.builder("fluxagent.alerts.resolved.retained", this, metrics -> metrics.retainedResolvedAlerts)
                .description("Resolved alerts retained in the bounded in-memory history, not a cumulative counter")
                .register(registry);
    }

    void update(Map<String, Object> agentStats, Map<String, Object> toolStats, int active, int resolved) {
        updateSeries(agents, agentStats, "agent", Map.of(
                "requests", values -> number(values, "total", 0),
                "success.ratio", values -> number(values, "success_rate", 1),
                "latency.seconds", values -> number(values, "avg_ms", 0) / 1000));
        updateSeries(tools, toolStats, "tool", Map.of(
                "requests", values -> number(values, "total", 0),
                "success.ratio", values -> number(values, "success_rate", 1),
                "latency.seconds", values -> number(values, "avg_latency_ms", 0) / 1000,
                "cache.hits", values -> number(values, "cache_hits", 0),
                "cache.hit.ratio", values -> number(values, "cache_hit_rate", 0),
                "circuit.state", values -> switch (String.valueOf(values.get("circuit_state"))) {
                    case "closed" -> 0;
                    case "open" -> 1;
                    case "half_open" -> 2;
                    default -> Double.NaN;
                }));
        activeAlerts = active;
        retainedResolvedAlerts = resolved;
    }

    private void updateSeries(Map<String, Series> series, Map<String, Object> snapshots, String label,
                              Map<String, ToDoubleFunction<Map<?, ?>>> metrics) {
        series.entrySet().removeIf(entry -> {
            if (snapshots.get(entry.getKey()) instanceof Map<?, ?>) {
                return false;
            }
            entry.getValue().meters.forEach(registry::remove);
            return true;
        });
        snapshots.forEach((name, value) -> {
            if (!(value instanceof Map<?, ?> snapshot)) {
                return;
            }
            Series item = series.get(name);
            if (item == null) {
                item = new Series();
                Series source = item;
                metrics.forEach((metric, extractor) -> source.meters.add(
                        Gauge.builder("fluxagent." + label + "." + metric, source,
                                        current -> extractor.applyAsDouble(current.values))
                                .tag(label, name).register(registry)));
                series.put(name, item);
            }
            item.values = Map.copyOf(snapshot);
        });
    }

    private static double number(Map<?, ?> values, String name, double fallback) {
        Object value = values.get(name);
        return value instanceof Number number && Double.isFinite(number.doubleValue())
                ? number.doubleValue() : fallback;
    }

    private static final class Series {
        private final List<Meter> meters = new ArrayList<>();
        private volatile Map<?, ?> values = Map.of();
    }
}
