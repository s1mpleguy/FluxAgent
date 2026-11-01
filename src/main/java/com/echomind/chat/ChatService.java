package com.echomind.chat;

import com.echomind.agent.AgentOrchestrator;
import com.echomind.agent.AgentRequest;
import com.echomind.agent.AgentType;
import com.echomind.agent.AnswerVerifier;
import com.echomind.agent.OrchestratorResult;
import com.echomind.api.dto.ChatRequest;
import com.echomind.api.dto.ChatResponse;
import com.echomind.intent.IntentCategory;
import com.echomind.intent.IntentRecognizer;
import com.echomind.intent.IntentResult;
import com.echomind.knowledge.SearchResult;
import com.echomind.memory.MemoryContext;
import com.echomind.memory.MemoryManager;
import com.echomind.memory.MessageRole;
import com.echomind.tool.KnowledgeToolManager;
import com.echomind.tool.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class ChatService {
    private final AgentOrchestrator orchestrator;
    private final IntentRecognizer intentRecognizer;
    private final MemoryManager memoryManager;
    private final KnowledgeToolManager knowledgeToolManager;
    private final AnswerVerifier answerVerifier;
    private final ObjectMapper objectMapper;
    private final Timer successTimer;
    private final Timer errorTimer;

    public ChatService(AgentOrchestrator orchestrator, IntentRecognizer intentRecognizer,
                       MemoryManager memoryManager, KnowledgeToolManager knowledgeToolManager,
                       AnswerVerifier answerVerifier, ObjectMapper objectMapper, MeterRegistry registry) {
        this.orchestrator = orchestrator;
        this.intentRecognizer = intentRecognizer;
        this.memoryManager = memoryManager;
        this.knowledgeToolManager = knowledgeToolManager;
        this.answerVerifier = answerVerifier;
        this.objectMapper = objectMapper;
        this.successTimer = timer(registry, "success");
        this.errorTimer = timer(registry, "error");
    }

    private Timer timer(MeterRegistry registry, String outcome) {
        return Timer.builder("echomind_chat_duration").tag("outcome", outcome)
                .publishPercentileHistogram()
                .serviceLevelObjectives(Duration.ofMillis(100), Duration.ofMillis(500), Duration.ofSeconds(1),
                        Duration.ofSeconds(3), Duration.ofSeconds(10), Duration.ofSeconds(30))
                .register(registry);
    }

    public ChatResponse chat(ChatRequest request) {
        long start = System.nanoTime();
        boolean success = false;
        try {
            if (request == null || request.message() == null || request.message().isBlank()) {
                throw new IllegalArgumentException("message 不能为空");
            }
            ChatResponse response = process(request, start);
            success = true;
            return response;
        } finally {
            (success ? successTimer : errorTimer).record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    private ChatResponse process(ChatRequest request, long start) {
        String userId = request.userIdOrDefault();
        String conversationId = request.conversationId() == null || request.conversationId().isBlank()
                ? UUID.randomUUID().toString()
                : request.conversationId();
        MemoryContext memoryContext = memoryManager.getContext(userId, conversationId, request.message());
        String memoryText = memoryContext.toPromptText(objectMapper);
        List<Map<String, String>> history = memoryContext.recentMessages().stream()
                .skip(Math.max(0, memoryContext.recentMessages().size() - 5))
                .map(m -> Map.of("role", m.role().name().toLowerCase(), "content", m.content()))
                .toList();
        IntentResult intentResult = intentRecognizer.recognize(request.message(), history);
        ToolResult<List<SearchResult>> knowledge = shouldUseKnowledge(intentResult.intent())
                ? knowledgeToolManager.searchWithRewrite(request.message(), 3)
                : new ToolResult<>(true, List.of(), "knowledge_search", null, false, 0, false);
        String knowledgeText = buildKnowledgeContext(knowledge.success() ? knowledge.data() : List.of());
        String fullContext = join(memoryText, knowledgeText);
        OrchestratorResult result = orchestrator.run(AgentRequest.of(request.message(), userId, conversationId, fullContext, history, intentResult));
        AnswerVerifier.VerificationResult verification = answerVerifier.verify(request.message(), result.response(), fullContext);
        boolean escalated = result.escalated() || verification.needEscalation();
        memoryManager.addMessage(userId, conversationId, MessageRole.USER, request.message());
        memoryManager.addMessage(userId, conversationId, MessageRole.ASSISTANT, result.response());
        memoryManager.updateProfile(userId, conversationId);
        return new ChatResponse(
                conversationId,
                result.response(),
                result.intent() == null ? "other" : result.intent().name().toLowerCase(),
                intentResult.intentGroup(),
                result.agentType().name().toLowerCase(),
                agentNames(result.agentTypes()),
                result.primaryAgent() == null ? null : result.primaryAgent().name().toLowerCase(Locale.ROOT),
                agentNames(result.supportingAgents()),
                result.routingReason(),
                result.routingConfidence(),
                escalated,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start),
                knowledge.success() && knowledge.data() != null && !knowledge.data().isEmpty(),
                verification.pass(),
                verification.grounded(),
                intentResult.entities(),
                round(intentResult.confidence(), 4),
                intentResult.sourceScores()
        );
    }

    private boolean shouldUseKnowledge(IntentCategory intent) {
        if (intent == null) {
            return false;
        }
        return switch (intent) {
            case QUERY,
                    COMPLAINT,
                    REQUEST,
                    TECHNICAL,
                    BILLING,
                    ACCOUNT,
                    ORDER_STATUS,
                    LOGISTICS,
                    REFUND,
                    INVOICE,
                    PAYMENT_ISSUE,
                    ACCOUNT_SECURITY,
                    TECHNICAL_LOGIN,
                    TECHNICAL_CRASH -> true;
            case GREETING, ESCALATION, HUMAN_HANDOFF, FEEDBACK, OTHER -> false;
        };
    }

    private String buildKnowledgeContext(List<SearchResult> results) {
        if (results == null || results.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        parts.add("[知识库检索结果]");
        for (int i = 0; i < results.size(); i++) {
            SearchResult item = results.get(i);
            parts.add((i + 1) + ". 标题: " + item.title() + "\n   相关度: " + item.score() + "\n   内容: " + item.content());
        }
        parts.add("请优先依据以上知识库内容回答；如果知识库内容不足，再结合通用客服能力说明。");
        return String.join("\n", parts);
    }

    private String join(String left, String right) {
        if (left == null || left.isBlank()) {
            return right == null ? "" : right;
        }
        if (right == null || right.isBlank()) {
            return left;
        }
        return left + "\n\n" + right;
    }

    private List<String> agentNames(List<AgentType> agentTypes) {
        if (agentTypes == null) {
            return List.of();
        }
        return agentTypes.stream()
                .map(agentType -> agentType.name().toLowerCase(Locale.ROOT))
                .toList();
    }

    private double round(double value, int digits) {
        double factor = Math.pow(10, digits);
        return Math.round(value * factor) / factor;
    }

}
