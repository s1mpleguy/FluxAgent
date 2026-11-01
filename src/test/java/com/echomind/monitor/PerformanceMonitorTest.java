package com.echomind.monitor;

import com.echomind.agent.AgentOrchestrator;
import com.echomind.agent.AgentType;
import com.echomind.agent.GeneralAgent;
import com.echomind.intent.IntentRecognizer;
import com.echomind.config.EchoMindProperties;
import com.echomind.tool.KnowledgeToolManager;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.*;

class PerformanceMonitorTest {
    private final AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
    private final KnowledgeToolManager tools = mock(KnowledgeToolManager.class);
    private final EchoMindProperties properties = new EchoMindProperties();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final PerformanceMonitor monitor = new PerformanceMonitor(orchestrator, tools, properties, registry);

    @Test
    void resolvesRecoveredAlertsAndAllowsRecurrence() {
        stats(10, 0.5, 100);
        monitor.collect();
        monitor.collect();
        assertThat(alerts("active_alerts")).hasSize(1);
        stats(20, 1.0, 100);
        monitor.collect();
        assertThat(alerts("active_alerts")).isEmpty();
        assertThat(alerts("resolved_alerts")).hasSize(1);
        assertThat(alerts("resolved_alerts").getFirst()).containsEntry("resolved", true);
        stats(30, 0.5, 100);
        monitor.collect();
        assertThat(alerts("active_alerts")).hasSize(1);
    }

    @Test
    void summaryIsAnImmutableSnapshotAcrossCollections() {
        stats(1, 0.5, 100);
        monitor.collect();
        List<Map<String, Object>> snapshot = alerts("active_alerts");
        stats(2, 0.5, 5000);
        monitor.collect();
        assertThat(snapshot).hasSize(1);
        assertThat(alerts("active_alerts")).hasSize(2);
    }

    @Test
    void routingPenaltyUsesConfiguredThresholds() {
        properties.getMonitor().setSuccessRateThreshold(0.5);
        properties.getMonitor().setLatencyMsThreshold(10000);
        stats(10, 0.8, 5000);
        monitor.collect();
        verify(orchestrator).updateRoutingPenalties(Map.of("general", 0.0));
    }

    @Test
    void publishesWeightedToolSuccessRate() {
        stats(1, 1, 100);
        when(tools.stats()).thenReturn(Map.of(
                "knowledge_search", Map.of("total", 10L, "success_rate", 0.5),
                "other", Map.of("total", 30L, "success_rate", 1.0)));
        monitor.collect();
        assertThat(registry.get("echomind_tool_success_rate").gauge().value()).isEqualTo(0.875);
    }

    @Test
    void roundedDisplayAveragesDoNotCauseFalseLatencySpikes() {
        var agent = new GeneralAgent((system, prompt, temperature, tokens) -> "ok", null);
        var realOrchestrator = new AgentOrchestrator(mock(IntentRecognizer.class), Map.of(AgentType.GENERAL, List.of(agent)));
        var realMonitor = new PerformanceMonitor(realOrchestrator, tools, properties, new SimpleMeterRegistry());
        when(tools.stats()).thenReturn(Map.of());
        for (int i = 0; i < 10000; i++) { agent.stats().record(true, 100); }
        for (int i = 0; i < 12; i++) {
            agent.stats().record(true, 100);
            realMonitor.collect();
        }
        for (int i = 0; i < 6; i++) {
            agent.stats().record(true, 101);
            realMonitor.collect();
        }
        assertThat((List<?>) realMonitor.summary().get("active_alerts")).isEmpty();
    }

    @Test
    void detectsLatencySpikeOnlyAfterNewRequests() {
        for (int i = 1; i <= 12; i++) {
            stats(i, 1, 100);
            monitor.collect();
        }
        stats(13, 1, 200);
        monitor.collect();
        assertThat(alerts("active_alerts")).anySatisfy(alert ->
                assertThat(alert.get("metric")).isEqualTo("agent_latency_zscore:general"));
        monitor.collect();
        assertThat(alerts("active_alerts")).hasSize(1);
    }

    private void stats(long total, double success, double avg) {
        when(orchestrator.stats()).thenReturn(Map.of("general", Map.of(
                "total", total, "success_rate", success, "avg_ms", avg)));
        when(tools.stats()).thenReturn(Map.of());
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> alerts(String key) {
        return (List<Map<String, Object>>) monitor.summary().get(key);
    }
}
