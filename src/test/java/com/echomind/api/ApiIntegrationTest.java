package com.echomind.api;

import com.echomind.llm.LlmGateway;
import com.echomind.memory.MemoryContext;
import com.echomind.memory.MemoryManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"ECHOMIND_ENV_FILE=", "echomind.jev.enabled=false"})
@AutoConfigureMockMvc
@AutoConfigureObservability
class ApiIntegrationTest {
    @Autowired MockMvc mvc;
    @MockitoBean MemoryManager memory;
    @MockitoBean LlmGateway llm;
    private static final Path DATA = tempDirectory();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("echomind.storage.knowledge-path", () -> DATA.resolve("knowledge.json").toString());
        registry.add("echomind.storage.memory-path", () -> DATA.resolve("memory.json").toString());
        registry.add("echomind.eval.baseline-path", () -> DATA.resolve("baseline.json").toString());
    }

    @BeforeEach
    void setup() {
        when(llm.chat(anyString(), anyString(), anyDouble(), anyInt())).thenReturn("{}");
        when(memory.getContext(anyString(), anyString(), anyString()))
                .thenReturn(new MemoryContext(List.of(), List.of(), Map.of(), ""));
    }

    @Test
    void exposesHealthSkillsAndChatWithCompatibleConversationFields() throws Exception {
        mvc.perform(get("/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ok"));
        mvc.perform(get("/skills")).andExpect(status().isOk());
        for (String field : List.of("conv_id", "conversation_id")) {
            mvc.perform(post("/chat").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"message\":\"你好\",\"user_id\":\"user\",\"" + field + "\":\"conversation\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.conv_id").value("conversation"));
        }
        mvc.perform(get("/metrics")).andExpect(status().isOk())
                .andExpect(content().string(containsString("echomind_chat_duration_seconds_bucket")))
                .andExpect(content().string(containsString("echomind_tool_success_rate")));
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andExpect(jsonPath("$.paths./chat").exists());
    }

    @Test
    void returnsBadRequestForInvalidSearchArguments() throws Exception {
        mvc.perform(post("/search").param("query", " ")).andExpect(status().isBadRequest());
        mvc.perform(post("/search").param("query", "退款").param("topK", "0")).andExpect(status().isBadRequest());
    }

    @Test
    void validatesFileTypesAndJsonDocuments() throws Exception {
        mvc.perform(multipart("/knowledge/upload").file(new MockMultipartFile("file", "image.png", "image/png", new byte[]{1})))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/knowledge/upload").file(new MockMultipartFile("file", "bad.json", "application/json", "[42]".getBytes())))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/knowledge/upload").file(new MockMultipartFile("file", "empty.md", "text/plain", new byte[0])))
                .andExpect(status().isBadRequest());
    }

    @Test
    void importsMarkdownAndJsonFiles() throws Exception {
        mvc.perform(multipart("/knowledge/upload").file(new MockMultipartFile("file", "POLICY.MD", "text/plain", "退款 policy".getBytes())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.added_chunks").value(1));
        mvc.perform(multipart("/knowledge/upload").file(new MockMultipartFile("file", "policy.json", "application/json",
                        "[{\"title\":\"policy\",\"content\":\"退款规则\"}]".getBytes())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.added_chunks").value(1));
    }

    private static Path tempDirectory() {
        try { return Files.createTempDirectory("flux-agent-api-test-"); }
        catch (java.io.IOException ex) { throw new IllegalStateException(ex); }
    }
}
