package com.fluxagent.tool;

import com.fluxagent.knowledge.KnowledgeBaseService;
import com.fluxagent.knowledge.SearchResult;
import com.fluxagent.llm.LlmGateway;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Service
public class KnowledgeToolManager implements AutoCloseable {
    private final KnowledgeBaseService knowledgeBaseService;
    private final LlmGateway llmGateway;
    private final ObjectMapper objectMapper;
    private final ToolExecutor executor;
    private final ToolDefinition<SearchOutput> definition;
    private final ExecutorService recallWorkers = Executors.newVirtualThreadPerTaskExecutor();

    public KnowledgeToolManager(KnowledgeBaseService knowledgeBaseService, LlmGateway llmGateway,
                                ObjectMapper objectMapper, ToolRegistry registry, ToolExecutor executor) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.llmGateway = llmGateway;
        this.objectMapper = objectMapper;
        this.executor = executor;
        JsonNode schema = objectMapper.valueToTree(Map.of(
                "type", "object", "required", List.of("query", "topK", "rewrite", "revision"),
                "additionalProperties", false,
                "properties", Map.of(
                        "query", Map.of("type", "string", "minLength", 1, "pattern", "\\S"),
                        "topK", Map.of("type", "integer", "minimum", 1, "maximum", 50),
                        "rewrite", Map.of("type", "boolean"),
                        "revision", Map.of("type", "integer", "minimum", 0))));
        this.definition = new ToolDefinition<>("knowledge_search", "知识库混合检索与可选查询改写、重排", schema,
                Duration.ofSeconds(30), Duration.ofMinutes(5), 5, Duration.ofSeconds(60),
                this::retrieve, (params, error) -> new SearchOutput(List.of(), false));
        registry.register(definition);
    }

    public ToolResult<List<SearchResult>> searchWithRewrite(String query, int topK) {
        return execute(query, topK, true);
    }

    public ToolResult<List<SearchResult>> search(String query, int topK) {
        return execute(query, topK, false);
    }

    private ToolResult<List<SearchResult>> execute(String query, int topK, boolean rewrite) {
        if (query == null || query.isBlank()) { throw new IllegalArgumentException("query 不能为空"); }
        var result = executor.execute(definition, Map.of("query", query, "topK", topK,
                "rewrite", rewrite, "revision", knowledgeBaseService.revision()));
        SearchOutput output = result.data() == null ? new SearchOutput(List.of(), false) : result.data();
        return new ToolResult<>(result.success(), output.results(), result.toolName(), result.error(),
                result.cached(), result.latencyMs(), output.reranked());
    }

    private SearchOutput retrieve(JsonNode parameters) throws Exception {
        String query = parameters.get("query").asText();
        int topK = parameters.get("topK").asInt();
        if (!parameters.get("rewrite").asBoolean()) {
            return new SearchOutput(List.copyOf(knowledgeBaseService.search(query, topK)), false);
        }
        List<String> queries = rewriteQuery(query);
        checkInterrupted();
        List<Future<List<SearchResult>>> futures = new ArrayList<>();
        try {
            for (String subQuery : queries) {
                futures.add(recallWorkers.submit(() -> knowledgeBaseService.search(subQuery, Math.max(topK, 5))));
            }
            Map<String, SearchResult> merged = new LinkedHashMap<>();
            for (Future<List<SearchResult>> future : futures) {
                for (SearchResult result : future.get()) {
                    merged.merge(result.id(), result, (left, right) -> left.score() >= right.score() ? left : right);
                }
            }
            checkInterrupted();
            SearchOutput output = rerank(query, new ArrayList<>(merged.values()), topK);
            checkInterrupted();
            return output;
        } finally {
            futures.forEach(future -> future.cancel(true));
        }
    }

    public Map<String, Object> stats() { return executor.stats(); }

    private List<String> rewriteQuery(String query) {
        String prompt = "将以下用户查询改写为 3 个不同角度的知识库搜索子查询，返回 JSON 数组。\n原始查询: " + query;
        try {
            String raw = llmGateway.chat("", prompt, 0.3, 256);
            List<String> rewritten = objectMapper.readValue(jsonArray(raw), new TypeReference<>() { });
            List<String> queries = new ArrayList<>();
            queries.add(query);
            rewritten.stream().filter(q -> q != null && !q.isBlank()).limit(3).forEach(queries::add);
            return queries.stream().distinct().toList();
        } catch (Exception ex) {
            return List.of(query);
        }
    }

    private SearchOutput rerank(String query, List<SearchResult> results, int topK) {
        List<SearchResult> sorted = results.stream()
                .sorted(Comparator.comparingDouble(SearchResult::score).reversed()).toList();
        if (results.size() <= topK) { return new SearchOutput(sorted, false); }
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            SearchResult result = results.get(i);
            String content = result.content() == null ? "" : result.content();
            items.append(i).append(". ").append(result.title()).append(" - ")
                    .append(content, 0, Math.min(content.length(), 180)).append('\n');
        }
        String prompt = "根据用户查询，对以下检索结果按相关性排序，只返回 JSON 索引数组。\n用户查询: "
                + query + "\n检索结果:\n" + items;
        try {
            String raw = llmGateway.chat("", prompt, 0.0, 256);
            List<Integer> order = objectMapper.readValue(jsonArray(raw), new TypeReference<>() { });
            Map<String, SearchResult> ranked = new LinkedHashMap<>();
            for (Integer index : order) {
                if (index != null && index >= 0 && index < results.size()) {
                    SearchResult item = results.get(index);
                    ranked.putIfAbsent(item.id(), item);
                }
            }
            if (!ranked.isEmpty()) {
                sorted.forEach(item -> ranked.putIfAbsent(item.id(), item));
                return new SearchOutput(ranked.values().stream().limit(topK).toList(), true);
            }
        } catch (Exception ignored) {
        }
        return new SearchOutput(sorted.stream().limit(topK).toList(), false);
    }

    private String jsonArray(String raw) {
        if (raw == null) { throw new IllegalArgumentException("Empty model response"); }
        int start = raw.indexOf('[');
        int end = raw.lastIndexOf(']');
        if (start < 0 || end < start) { throw new IllegalArgumentException("Missing JSON array"); }
        return raw.substring(start, end + 1);
    }

    private void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) { throw new InterruptedException("Knowledge search cancelled"); }
    }

    @Override
    @PreDestroy
    public void close() { recallWorkers.shutdownNow(); }

    private record SearchOutput(List<SearchResult> results, boolean reranked) { }
}
