package com.fluxagent.intent;

import com.fluxagent.llm.LlmGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IntentRecognizerTest {
    private final LlmGateway llm = mock(LlmGateway.class);
    private final JevIntentClient jev = mock(JevIntentClient.class);
    private final IntentRecognizer recognizer = new IntentRecognizer(llm, new ObjectMapper(), jev);

    @Test
    void usesJevWithoutChatClassificationAndKeepsEntitiesUrgencyAndCache() {
        String message = "订单12345要退款，紧急";
        var history = List.of(Map.of("role", "user", "content", "你好"));
        when(jev.classify(message, history)).thenReturn(Optional.of(new JevIntentClient.Prediction(IntentCategory.REFUND, 0.96)));
        IntentResult result = recognizer.recognize(message, history);
        assertEquals(IntentCategory.REFUND, result.intent());
        assertEquals(0.96, result.confidence());
        assertEquals(0.96, result.sourceScores().get("jev"));
        assertEquals(UrgencyLevel.CRITICAL, result.urgency());
        assertEquals("billing", result.intentGroup());
        assertTrue(result.entities().get("order_id").contains("12345"));
        assertSame(result, recognizer.recognize(message, history));
        verify(jev, times(1)).classify(message, history);
        verifyNoInteractions(llm);
    }

    @Test
    void fallsBackToExistingChatClassificationWhenJevCannotDecide() {
        when(jev.classify(anyString(), isNull())).thenReturn(Optional.empty());
        when(llm.chat(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("{\"intent\":\"refund\",\"confidence\":0.99,\"reasoning\":\"退款请求\"}");
        IntentResult result = recognizer.recognize("我要申请退款", null);
        assertEquals(IntentCategory.REFUND, result.intent());
        assertTrue(result.sourceScores().containsKey("llm"));
        assertFalse(result.sourceScores().containsKey("jev"));
        verify(llm).chat(anyString(), anyString(), anyDouble(), anyInt());
    }

    @Test
    void keepsLocalRecognitionWhenBothRemoteModelsAreUnavailable() {
        when(jev.classify(anyString(), isNull())).thenReturn(Optional.empty());
        when(llm.chat(anyString(), anyString(), anyDouble(), anyInt())).thenThrow(new IllegalStateException("unavailable"));
        assertEquals(IntentCategory.REFUND, recognizer.recognize("我要申请退款", null).intent());
    }
}
