package com.echomind.chat;

import com.echomind.agent.*;
import com.echomind.api.dto.ChatRequest;
import com.echomind.intent.*;
import com.echomind.memory.*;
import com.echomind.tool.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatServiceTest {
    private final AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
    private final IntentRecognizer intent = mock(IntentRecognizer.class);
    private final MemoryManager memory = mock(MemoryManager.class);
    private final KnowledgeToolManager knowledge = mock(KnowledgeToolManager.class);
    private final AnswerVerifier verifier = mock(AnswerVerifier.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ChatService service = new ChatService(orchestrator, intent, memory, knowledge, verifier,
            new ObjectMapper(), registry);

    @BeforeEach
    void setup() {
        when(memory.getContext(anyString(), anyString(), anyString())).thenReturn(new MemoryContext(List.of(), List.of(), Map.of(), ""));
        when(intent.recognize(anyString(), anyList())).thenReturn(new IntentResult(IntentCategory.REFUND, .9,
                UrgencyLevel.LOW, "billing", Map.of(), "", 0, Map.of()));
        when(knowledge.searchWithRewrite(anyString(), anyInt())).thenReturn(new ToolResult<>(false, List.of(),
                "knowledge_search", "timeout", false, 1, false));
        when(orchestrator.run(any())).thenReturn(new OrchestratorResult("request", "请提供订单号", AgentType.BILLING,
                IntentCategory.REFUND, false, 1, List.of(AgentType.BILLING), AgentType.BILLING, List.of(), "refund", .9));
        when(verifier.verify(anyString(), anyString(), anyString())).thenReturn(new AnswerVerifier.VerificationResult(true, false, true, ""));
    }

    @Test
    void preservesConversationAndWritesBothMessagesEvenWhenKnowledgeFails() {
        var response = service.chat(new ChatRequest("退款", "user", "conversation"));
        assertThat(response.conversationId()).isEqualTo("conversation");
        assertThat(response.knowledgeUsed()).isFalse();
        assertThat(response.escalated()).isTrue();
        verify(memory).addMessage("user", "conversation", MessageRole.USER, "退款");
        verify(memory).addMessage("user", "conversation", MessageRole.ASSISTANT, "请提供订单号");
        verify(memory).updateProfile("user", "conversation");
        assertThat(registry.get("echomind_chat_duration").tag("outcome", "success").timer().count()).isEqualTo(1);
    }

    @Test
    void generatesConversationIdAndDefaultsAnonymousUser() {
        var response = service.chat(new ChatRequest("退款", null, null));
        assertThat(response.conversationId()).isNotBlank();
        verify(memory).getContext("anonymous", response.conversationId(), "退款");
    }

    @Test
    void rejectsBlankMessagesOutsideHttpValidationAndRecordsErrors() {
        assertThatThrownBy(() -> service.chat(new ChatRequest(" ", null, null))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(orchestrator);
        assertThat(registry.get("echomind_chat_duration").tag("outcome", "error").timer().count()).isEqualTo(1);
    }
}
