package com.fluxagent.tool;

import com.fluxagent.config.FluxAgentProperties;
import com.fluxagent.knowledge.KnowledgeBaseService;
import com.fluxagent.knowledge.SearchResult;
import com.fluxagent.llm.LlmGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.ai.vectorstore.VectorStore;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KnowledgeToolManagerTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ToolRegistry registry = new ToolRegistry();
    private final ToolExecutor executor = new ToolExecutor(registry, mapper);
    private final LlmGateway llm = mock(LlmGateway.class);
    private KnowledgeToolManager manager;

    @AfterEach
    void close() {
        if (manager != null) { manager.close(); }

        executor.close();
    }

    @Test
    void retrievalFailureIsNotReportedAsSuccessfulKnowledge() {
        var knowledge = mock(KnowledgeBaseService.class);
        when(knowledge.search(anyString(), anyInt())).thenThrow(new IllegalStateException("storage unavailable"));
        manager = new KnowledgeToolManager(knowledge, llm, mapper, registry, executor);
        var result = manager.searchWithRewrite("退款", 3);
        assertThat(result.success()).isFalse();
        assertThat(result.data()).isEmpty();
        assertThat(result.reranked()).isFalse();
        assertThat(((Map<?, ?>) manager.stats().get("knowledge_search")).get("success_rate")).isEqualTo(0.0);
    }

    @Test
    void incompleteRerankingIsFilledWithRemainingCandidates() {
        var knowledge = mock(KnowledgeBaseService.class);
        when(knowledge.search(anyString(), anyInt())).thenReturn(List.of(result("a", .9), result("b", .8), result("c", .7)));
        when(llm.chat(anyString(), contains("子查询"), anyDouble(), anyInt())).thenReturn("[]");
        when(llm.chat(anyString(), contains("索引数组"), anyDouble(), anyInt())).thenReturn("[2,2,999]");
        manager = new KnowledgeToolManager(knowledge, llm, mapper, registry, executor);
        var result = manager.searchWithRewrite("退款", 2);
        assertThat(result.data()).extracting(SearchResult::id).containsExactly("c", "a");
        assertThat(result.reranked()).isTrue();
    }

    @Test
    void invalidRerankingFallsBackToScoresWithoutClaimingRerank() {
        var knowledge = mock(KnowledgeBaseService.class);
        when(knowledge.search(anyString(), anyInt())).thenReturn(List.of(result("a", .7), result("b", .9)));
        manager = new KnowledgeToolManager(knowledge, llm, mapper, registry, executor);
        var result = manager.searchWithRewrite("退款", 1);
        assertThat(result.data()).extracting(SearchResult::id).containsExactly("b");
        assertThat(result.reranked()).isFalse();
    }

    @Test
    void importedKnowledgeInvalidatesCachedResults(@TempDir Path directory) {
        var properties = new FluxAgentProperties();
        properties.getStorage().setKnowledgePath(directory.resolve("knowledge.json").toString());
        var knowledge = new KnowledgeBaseService(properties,
                new StaticListableBeanFactory().getBeanProvider(VectorStore.class), mapper);
        manager = new KnowledgeToolManager(knowledge, llm, mapper, registry, executor);
        assertThat(manager.search("退款", 3).data()).isEmpty();
        assertThat(manager.search("退款", 3).cached()).isTrue();
        knowledge.addDocuments(List.of(Map.of("title", "退款政策", "content", "退款申请通过后原路退回。")));
        assertThat(manager.search("退款", 3).data()).isNotEmpty();
    }

    private SearchResult result(String id, double score) {
        return new SearchResult(id, id, "退款 policy " + id, score, 0, Map.of());
    }
}
