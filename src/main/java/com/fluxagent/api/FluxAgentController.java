package com.fluxagent.api;

import com.fluxagent.agent.AgentOrchestrator;
import com.fluxagent.chat.ChatService;
import com.fluxagent.api.dto.BatchDocInput;
import com.fluxagent.api.dto.ChatRequest;
import com.fluxagent.api.dto.ChatResponse;
import com.fluxagent.api.dto.EvalRunRequest;
import com.fluxagent.evaluation.EndToEndEvaluator;
import com.fluxagent.knowledge.KnowledgeBaseService;
import com.fluxagent.knowledge.SearchResult;
import com.fluxagent.monitor.PerformanceMonitor;
import com.fluxagent.skill.SkillManager;
import com.fluxagent.tool.KnowledgeToolManager;
import com.fluxagent.tool.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

@RestController
@Tag(name = "FluxAgent API", description = "智能客服对话、知识库、监控和评测接口")
public class FluxAgentController {

    private final AgentOrchestrator orchestrator;
    private final ChatService chatService;
    private final KnowledgeToolManager knowledgeToolManager;
    private final KnowledgeBaseService knowledgeBaseService;
    private final PerformanceMonitor performanceMonitor;
    private final EndToEndEvaluator evaluator;
    private final SkillManager skillManager;
    private final ObjectMapper objectMapper;
    private final PrometheusMeterRegistry prometheusMeterRegistry;

    public FluxAgentController(
            AgentOrchestrator orchestrator,
            ChatService chatService,
            KnowledgeToolManager knowledgeToolManager,
            KnowledgeBaseService knowledgeBaseService,
            PerformanceMonitor performanceMonitor,
            EndToEndEvaluator evaluator,
            SkillManager skillManager,
            ObjectMapper objectMapper,
            PrometheusMeterRegistry prometheusMeterRegistry
    ) {
        this.orchestrator = orchestrator;
        this.chatService = chatService;
        this.knowledgeToolManager = knowledgeToolManager;
        this.knowledgeBaseService = knowledgeBaseService;
        this.performanceMonitor = performanceMonitor;
        this.evaluator = evaluator;
        this.skillManager = skillManager;
        this.objectMapper = objectMapper;
        this.prometheusMeterRegistry = prometheusMeterRegistry;
    }

    @GetMapping("/health")
    @Operation(summary = "健康检查", description = "返回服务状态和 Agent 路由统计。")
    public Map<String, Object> health() {
        return Map.of("status", "ok", "agents", orchestrator.stats());
    }

    @PostMapping("/chat")
    @Operation(summary = "智能客服对话", description = "执行完整对话链路：记忆读取、知识库检索、意图识别、多 Agent 路由、回答校验和记忆写入。")
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        return chatService.chat(request);
    }

    @PostMapping("/search")
    @Operation(summary = "知识库检索", description = "对用户查询做查询改写、并行召回和 LLM rerank。")
    public Map<String, Object> search(
            @Parameter(description = "检索关键词或用户问题", example = "退款多久能到账") @RequestParam String query,
            @Parameter(description = "返回结果数量", example = "5") @RequestParam(defaultValue = "5") int topK) {
        ToolResult<List<SearchResult>> result = knowledgeToolManager.searchWithRewrite(query, topK);
        return Map.of("query", query, "results", result.data(), "reranked", result.reranked(),
                "success", result.success(), "error", result.error() == null ? "" : result.error());
    }

    @PostMapping("/knowledge/add")
    @Operation(summary = "批量添加知识文档", description = "将文档切片后写入 Java 版持久化知识库。")
    public Map<String, Object> addKnowledge(@Valid @RequestBody BatchDocInput input) {
        List<Map<String, String>> docs = input.documents().stream()
                .map(d -> Map.of("title", d.title(), "content", d.content()))
                .toList();
        int added = knowledgeBaseService.addDocuments(docs);
        return Map.of("added_chunks", added, "total_chunks", knowledgeBaseService.docCount());
    }

    @PostMapping(value = "/knowledge/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "上传知识库文件", description = "支持上传 .txt、.md、.json 文件，最大 10MB。JSON 文件格式为数组：[{\"title\":\"...\",\"content\":\"...\"}]。")
    public Map<String, Object> uploadKnowledge(@Parameter(description = "知识库文件") @RequestParam("file") MultipartFile file) throws Exception {
        if (file.getSize() > 10L * 1024 * 1024) {
            throw new MaxUploadSizeExceededException(10L * 1024 * 1024);
        }
        String filename = file.getOriginalFilename() == null ? "unknown" : file.getOriginalFilename();
        String normalized = filename.toLowerCase(Locale.ROOT);
        if (!normalized.endsWith(".txt") && !normalized.endsWith(".md") && !normalized.endsWith(".json")) {
            throw new IllegalArgumentException("仅支持 .txt、.md、.json 文件");
        }
        String text = new String(file.getBytes(), java.nio.charset.StandardCharsets.UTF_8);
        if (text.isBlank()) { throw new IllegalArgumentException("文件内容不能为空"); }
        List<Map<String, String>> docs;
        if (normalized.endsWith(".json")) {
            docs = parseJsonDocs(text);
        } else {
            String title = filename.contains(".") ? filename.substring(0, filename.lastIndexOf('.')) : filename;
            docs = List.of(Map.of("title", title, "content", text));
        }
        int added = knowledgeBaseService.addDocuments(docs);
        return Map.of("message", "文件 " + filename + " 导入成功", "added_chunks", added, "total_chunks", knowledgeBaseService.docCount());
    }

    @GetMapping("/knowledge/stats")
    @Operation(summary = "知识库统计", description = "返回当前知识库片段数量。")
    public Map<String, Object> knowledgeStats() {
        return Map.of("total_chunks", knowledgeBaseService.docCount());
    }

    @GetMapping("/monitor")
    @Operation(summary = "监控摘要", description = "返回 Agent 指标、工具统计、告警和优化建议。")
    public Map<String, Object> monitor() {
        return performanceMonitor.summary();
    }

    @GetMapping("/skills")
    @Operation(summary = "Skills 摘要", description = "查看当前已加载的动态 Skills，便于确认热加载结果和排查解析错误。")
    public Map<String, Object> skills() {
        return skillManager.summary();
    }

    @PostMapping("/skills/reload")
    @Operation(summary = "重新加载 Skills", description = "运行时重新扫描 Skill 目录，不需要重启服务。")
    public Map<String, Object> reloadSkills() {
        skillManager.reload();
        return skillManager.summary();
    }

    @GetMapping(value = "/metrics", produces = "text/plain; version=0.0.4; charset=utf-8")
    @Operation(summary = "Prometheus 指标", description = "返回 Prometheus 文本格式指标，兼容 Python 版 /metrics 路径。")
    public ResponseEntity<String> metrics() {
        return ResponseEntity.ok(prometheusMeterRegistry.scrape());
    }

    @PostMapping(value = "/eval/run", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "运行评测", description = "运行意图识别和对话质量评测；请求体为空时使用内置默认用例。")
    public Map<String, Object> eval(@RequestBody(required = false) EvalRunRequest request) {
        return evaluator.run(request);
    }

    private List<Map<String, String>> parseJsonDocs(String text) throws Exception {
        com.fasterxml.jackson.databind.JsonNode values;
        try {
            values = objectMapper.readTree(text);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalArgumentException("JSON 文档格式无效", ex);
        }
        if (values == null || !values.isArray() || values.isEmpty()) {
            throw new IllegalArgumentException("JSON 文档必须是非空数组");
        }
        List<Map<String, String>> docs = new ArrayList<>();
        for (var value : values) {
            if (!value.isObject() || !value.path("content").isTextual() || value.path("content").asText().isBlank()
                    || (value.has("title") && (!value.get("title").isTextual() || value.get("title").asText().isBlank()))) {
                throw new IllegalArgumentException("每条文档需要非空文本 content，title 如提供也必须为非空文本");
            }
            docs.add(Map.of("title", value.path("title").asText("未命名文档"), "content", value.get("content").asText()));
        }
        return docs;
    }
}
