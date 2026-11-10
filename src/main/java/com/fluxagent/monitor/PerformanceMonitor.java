package com.fluxagent.monitor;

import com.fluxagent.agent.AgentOrchestrator;
import com.fluxagent.config.FluxAgentProperties;
import com.fluxagent.tool.KnowledgeToolManager;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class PerformanceMonitor {
    private final AgentOrchestrator orchestrator;
    private final KnowledgeToolManager toolManager;
    private final FluxAgentProperties properties;
    private final Map<String, Map<String, Object>> activeAlerts = new LinkedHashMap<>();
    private final ArrayDeque<Map<String, Object>> resolvedAlerts = new ArrayDeque<>();
    private final Map<String, LatencyWindow> latencyWindows = new HashMap<>();
    private final RestClient restClient;
    private final Counter monitorCollections;
    private final Timer monitorCollectionTimer;
    private final RuntimeMetrics runtimeMetrics;
    private volatile double agentSuccessRate = 1.0;
    private volatile double toolSuccessRate = 1.0;

    public PerformanceMonitor(AgentOrchestrator orchestrator, KnowledgeToolManager toolManager,
                              FluxAgentProperties properties, MeterRegistry meterRegistry) {
        this.orchestrator = orchestrator;
        this.toolManager = toolManager;
        this.properties = properties;
        this.runtimeMetrics = new RuntimeMetrics(meterRegistry);
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(2));
        this.restClient = RestClient.builder().requestFactory(factory).build();
        Gauge.builder("echomind_agent_success_rate", () -> agentSuccessRate).register(meterRegistry);
        Gauge.builder("echomind_tool_success_rate", () -> toolSuccessRate).register(meterRegistry);
        this.monitorCollections = Counter.builder("echomind_monitor_collections_total").register(meterRegistry);
        this.monitorCollectionTimer = Timer.builder("echomind_monitor_collection_duration").register(meterRegistry);
    }

    @PostConstruct
    public void init() { collect(); }

    @Scheduled(fixedDelay = 10000)
    public void collect() {
        monitorCollectionTimer.record(() -> {
            monitorCollections.increment();
            List<Map<String, Object>> notifications = collectMetrics();
            // Network calls must not hold the lock used by /monitor.
            notifications.forEach(this::sendWebhook);
        });
    }

    private synchronized List<Map<String, Object>> collectMetrics() {
        List<Map<String, Object>> notifications = new ArrayList<>();
        Map<String, Object> agentStats = orchestrator.stats();
        Map<String, Double> penalties = new HashMap<>();
        agentSuccessRate = 1.0;
        var config = properties.getMonitor();
        agentStats.forEach((key, value) -> {
            if (value instanceof Map<?, ?> map) {
                double sr = asDouble(map.get("success_rate"), 1.0);
                double avg = asDouble(map.get("avg_ms"), 0.0);
                long total = (long) asDouble(map.get("total"), 0);
                double totalLatency = asDouble(map.get("total_latency_ms"), avg * total);
                agentSuccessRate = Math.min(agentSuccessRate, sr);
                updateAlert("agent_success_rate:" + key, sr, config.getSuccessRateThreshold(),
                        sr < config.getSuccessRateThreshold(), notifications);
                updateAlert("agent_avg_ms:" + key, avg, config.getLatencyMsThreshold(),
                        avg > config.getLatencyMsThreshold(), notifications);
                LatencyWindow window = latencyWindows.computeIfAbsent(key, ignored -> new LatencyWindow());
                Double zscore = window.observe(total, totalLatency, config.getAnomalyWindowSize(), config.getAnomalyMinSamples());
                if (zscore != null) {
                    updateAlert("agent_latency_zscore:" + key, zscore, config.getZscoreThreshold(),
                            zscore > config.getZscoreThreshold(), notifications);
                }
                penalties.put(key, routingPenalty(sr, avg));
            }
        });
        latencyWindows.keySet().retainAll(agentStats.keySet());
        orchestrator.updateRoutingPenalties(penalties);
        double total = 0;
        double successes = 0;
        Map<String, Object> toolStats = toolManager.stats();
        for (Object value : toolStats.values()) {
            if (value instanceof Map<?, ?> map) {
                double count = asDouble(map.get("total"), 0);
                total += count;
                successes += count * asDouble(map.get("success_rate"), 1);
            }
        }
        toolSuccessRate = total == 0 ? 1 : successes / total;
        runtimeMetrics.update(agentStats, toolStats, activeAlerts.size(), resolvedAlerts.size());
        return notifications;
    }

    public synchronized Map<String, Object> summary() {
        return Map.of(
                "agent_stats", orchestrator.stats(),
                "tool_stats", toolManager.stats(),
                "active_alerts", List.copyOf(activeAlerts.values()),
                "resolved_alerts", List.copyOf(resolvedAlerts),
                "suggestions", activeAlerts.isEmpty() ? List.of() : List.of(Map.of(
                        "title", "检查异常 Agent 的成功率和延迟",
                        "action", "路由权重已根据配置阈值调整，请检查模型服务和 prompt。", "priority", 7))
        );
    }

    private double routingPenalty(double successRate, double avgMs) {
        var config = properties.getMonitor();
        double penalty = Math.min(0.5, Math.max(0, config.getSuccessRateThreshold() - successRate) * 2);
        penalty += Math.min(0.4, Math.max(0, avgMs - config.getLatencyMsThreshold()) / 10000);
        return Math.min(0.9, penalty);
    }

    private void updateAlert(String metric, double value, double threshold, boolean breached,
                             List<Map<String, Object>> notifications) {
        Map<String, Object> previous = activeAlerts.get(metric);
        if (breached) {
            Map<String, Object> alert = Map.of("metric", metric, "value", value, "threshold", threshold,
                    "resolved", false, "triggered_at", previous == null ? Instant.now().toString() : previous.get("triggered_at"));
            activeAlerts.put(metric, alert);
            if (previous == null) { notifications.add(alert); }
        } else if (previous != null) {
            activeAlerts.remove(metric);
            Map<String, Object> resolved = new LinkedHashMap<>(previous);
            resolved.put("resolved", true);
            resolved.put("value", value);
            resolved.put("resolved_at", Instant.now().toString());
            Map<String, Object> snapshot = Map.copyOf(resolved);
            resolvedAlerts.addLast(snapshot);
            while (resolvedAlerts.size() > 100) { resolvedAlerts.removeFirst(); }
            notifications.add(snapshot);
        }
    }

    private void sendWebhook(Map<String, Object> alert) {
        String url = properties.getMonitor().getWebhookUrl();
        if (url == null || url.isBlank()) { return; }
        try {
            restClient.post().uri(url).body(alert).retrieve().toBodilessEntity();
        } catch (Exception ignored) {
            // Alert state remains available through /monitor if delivery fails.
        }
    }

    private double asDouble(Object value, double fallback) {
        return value instanceof Number number && Double.isFinite(number.doubleValue()) ? number.doubleValue() : fallback;
    }

    private static final class LatencyWindow {
        private final ArrayDeque<Double> samples = new ArrayDeque<>();
        private long previousCount;
        private double previousLatency;

        Double observe(long count, double cumulativeLatency, int capacity, int minimum) {
            if (count < previousCount) {
                samples.clear();
                previousCount = 0;
                previousLatency = 0;
            }
            if (count == previousCount) { return null; }
            double sample = Math.max(0, (cumulativeLatency - previousLatency) / (count - previousCount));
            previousCount = count;
            previousLatency = cumulativeLatency;
            double score = 0;
            if (samples.size() >= Math.max(2, minimum)) {
                double mean = samples.stream().mapToDouble(Double::doubleValue).average().orElse(0);
                double variance = samples.stream().mapToDouble(v -> (v - mean) * (v - mean)).average().orElse(0);
                score = (sample - mean) / Math.max(1.0, Math.sqrt(variance));
            }
            samples.addLast(sample);
            while (samples.size() > Math.max(2, capacity)) { samples.removeFirst(); }
            return score;
        }
    }
}
