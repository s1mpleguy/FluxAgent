package com.fluxagent.memory;

import com.fluxagent.config.FluxAgentProperties;
import com.fluxagent.llm.LlmGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MemoryManagerTest {
    @Test
    @SuppressWarnings("unchecked")
    void compressionPreservesChronologicalOrderOfRecentMessages(@TempDir Path directory) {
        var redis = mock(StringRedisTemplate.class);
        ListOperations<String, String> lists = mock(ListOperations.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForList()).thenReturn(lists);
        when(redis.opsForValue()).thenReturn(values);
        LinkedList<String> stored = new LinkedList<>();
        when(lists.leftPush(anyString(), anyString())).thenAnswer(call -> {
            stored.addFirst(call.getArgument(1)); return (long) stored.size();
        });
        when(lists.size(anyString())).thenAnswer(call -> (long) stored.size());
        when(lists.range(anyString(), anyLong(), anyLong())).thenAnswer(call -> new ArrayList<>(stored));
        when(redis.delete(anyString())).thenAnswer(call -> { stored.clear(); return true; });
        doAnswer(call -> {
            long end = call.getArgument(2);
            while (stored.size() > end + 1) { stored.removeLast(); }
            return null;
        }).when(lists).trim(anyString(), eq(0L), anyLong());
        var properties = new FluxAgentProperties();
        properties.getStorage().setMemoryPath(directory.resolve("memory.json").toString());
        properties.getMemory().setCompressAt(6);
        LlmGateway llm = (system, prompt, temperature, tokens) -> "历史摘要";
        var memory = new MemoryManager(redis, new ObjectMapper().findAndRegisterModules(), properties, llm);
        for (int i = 1; i <= 6; i++) { memory.addMessage("user", "conversation", MessageRole.USER, "message-" + i); }
        assertThat(memory.getWorkingMemory("user", "conversation")).extracting(ConversationMessage::content)
                .containsExactly("message-2", "message-3", "message-4", "message-5", "message-6");
        verify(redis, never()).delete(anyString());
    }
}
