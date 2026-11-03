package com.fluxagent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "ECHOMIND_ENV_FILE=", "spring.profiles.active=deepseek",
        "echomind.jev.enabled=false", "echomind.llm.fallback-enabled=false",
        "spring.ai.retry.max-attempts=1"
})
@AutoConfigureObservability
class DeepSeekGatewayIntegrationTest {
    private static final AtomicReference<String> REQUEST_PATH = new AtomicReference<>();
    private static final AtomicReference<String> AUTHORIZATION = new AtomicReference<>();
    private static final AtomicReference<String> REQUEST_BODY = new AtomicReference<>();
    private static final HttpServer SERVER = startServer();
    private static final Path DATA = tempDirectory();

    @Autowired LlmGateway gateway;
    @Autowired ObjectMapper mapper;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.deepseek.base-url", () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
        registry.add("spring.ai.deepseek.api-key", () -> "local-gateway-test-key");
        registry.add("echomind.storage.knowledge-path", () -> DATA.resolve("knowledge.json").toString());
        registry.add("echomind.storage.memory-path", () -> DATA.resolve("memory.json").toString());
        registry.add("echomind.eval.baseline-path", () -> DATA.resolve("baseline.json").toString());
    }

    @AfterAll
    static void stopServer() {
        SERVER.stop(0);
    }

    @Test
    void sendsConfiguredModelToDmxCompatibleChatEndpoint() throws Exception {
        assertEquals("OK", gateway.chat("请简短回答", "你好", 0.2, 64));
        assertEquals("/v1/chat/completions", REQUEST_PATH.get());
        assertEquals("Bearer local-gateway-test-key", AUTHORIZATION.get());
        JsonNode body = mapper.readTree(REQUEST_BODY.get());
        assertEquals("deepseek-v4.1-flash-1017", body.path("model").asText());
        assertEquals("system", body.path("messages").get(0).path("role").asText());
        assertEquals("你好", body.path("messages").get(1).path("content").asText());
        assertEquals(64, body.path("max_tokens").asInt());
        assertFalse(body.path("stream").asBoolean());
    }

    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                try (exchange) {
                    REQUEST_PATH.set(exchange.getRequestURI().getPath());
                    AUTHORIZATION.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    REQUEST_BODY.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    boolean valid = "/v1/chat/completions".equals(REQUEST_PATH.get())
                            && "POST".equals(exchange.getRequestMethod());
                    String response = valid ? """
                            {"id":"local-chat","object":"chat.completion","created":1,
                             "model":"deepseek-v4.1-flash-1017",
                             "choices":[{"index":0,"message":{"role":"assistant","content":"OK"},"finish_reason":"stop"}],
                             "usage":{"prompt_tokens":2,"completion_tokens":1,"total_tokens":3}}
                            """ : "{\"error\":{\"message\":\"unsupported chat path\"}}";
                    byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(valid ? 200 : 404, bytes.length);
                    exchange.getResponseBody().write(bytes);
                }
            });
            server.start();
            return server;
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static Path tempDirectory() {
        try { return Files.createTempDirectory("flux-agent-gateway-test-"); }
        catch (IOException ex) { throw new IllegalStateException(ex); }
    }
}
