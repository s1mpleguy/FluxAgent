package com.fluxagent.intent;

import com.fluxagent.config.FluxAgentProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class JevIntentClientTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final FluxAgentProperties properties = new FluxAgentProperties();
    private final AtomicReference<JsonNode> request = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private HttpServer server;
    private JevIntentClient client;
    private volatile int status = 200;
    private volatile String response = """
            {"model":"jev-1.14.0","answers":{"intent":{
              "type":"choice","choice":"refund","confidence":0.95}}}
            """;
    private volatile long delayMs;
    private volatile long bodyDelayMs;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(workers);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            path.set(exchange.getRequestURI().getPath());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            request.set(mapper.readTree(exchange.getRequestBody()));
            try {
                Thread.sleep(delayMs);
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes, 0, 1);
                exchange.getResponseBody().flush();
                Thread.sleep(bodyDelayMs);
                exchange.getResponseBody().write(bytes, 1, bytes.length - 1);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        properties.getJev().setEnabled(true);
        properties.getJev().setApiKey("test-dmx-key");
        properties.getJev().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        properties.getJev().setModel("jev-latest");
        properties.getJev().setTimeout(Duration.ofSeconds(2));
    }

    @AfterEach
    void stopServer() {
        if (client != null) { client.close(); }
        server.stop(0);
        workers.shutdownNow();
    }

    @Test
    void sendsDmxProtocolAndReadsChoiceWithConversationContext() {
        client = new JevIntentClient(properties, mapper);
        var prediction = client.classify("退款多久到账？", List.of(Map.of("role", "user", "content", "订单12345"))).orElseThrow();
        assertEquals(IntentCategory.REFUND, prediction.intent());
        assertEquals(0.95, prediction.confidence());
        assertEquals("/typesafe/v1/systemone", path.get());
        assertEquals("Bearer test-dmx-key", authorization.get());
        JsonNode body = request.get();
        assertEquals("jev-latest", body.path("model").asText());
        assertEquals("退款多久到账？", body.path("state").path("message").asText());
        assertEquals("订单12345", body.path("state").path("history").get(0).path("content").asText());
        assertTrue(body.path("questions").isObject());
        JsonNode question = body.path("questions").path("intent");
        assertEquals("choice", question.path("type").asText());
        assertFalse(question.path("instructions").asText().isBlank());
        assertEquals(IntentCategory.values().length, question.path("criteria").size());
        assertTrue(question.path("criteria").has("refund"));
    }

    @Test
    void skipsNetworkWhenDisabledOrCredentialsMissing() {
        properties.getJev().setEnabled(false);
        client = new JevIntentClient(properties, mapper);
        assertTrue(client.classify("退款", null).isEmpty());
        properties.getJev().setEnabled(true);
        properties.getJev().setApiKey("");
        assertTrue(client.classify("退款", null).isEmpty());
        assertEquals(0, calls.get());
    }

    @Test
    void rejectsUnknownChoicesAndInvalidConfidence() {
        client = new JevIntentClient(properties, mapper);
        for (String answer : List.of(
                "{\"type\":\"choice\",\"choice\":\"invented\",\"confidence\":0.99}",
                "{\"type\":\"choice\",\"choice\":\"refund\"}",
                "{\"type\":\"choice\",\"choice\":\"refund\",\"confidence\":\"high\"}",
                "{\"type\":\"choice\",\"choice\":\"refund\",\"confidence\":1.5}",
                "{\"type\":\"choice\",\"choice\":\"refund\",\"confidence\":0.2}",
                "{\"type\":\"score\",\"choice\":\"refund\",\"confidence\":0.99}")) {
            response = "{\"answers\":{\"intent\":" + answer + "}}";
            assertTrue(client.classify("退款", null).isEmpty(), answer);
        }
    }

    @Test
    void returnsEmptyOnProviderErrorsOrMalformedResponses() {
        client = new JevIntentClient(properties, mapper);
        status = 401;
        response = "provider error: test-dmx-key";
        assertTrue(client.classify("退款", null).isEmpty());
        status = 200;
        assertTrue(client.classify("退款", null).isEmpty());
        response = "{}";
        assertTrue(client.classify("退款", null).isEmpty());
    }

    @Test
    void timesOutWithoutBlockingFallback() {
        properties.getJev().setTimeout(Duration.ofMillis(80));
        delayMs = 500;
        client = new JevIntentClient(properties, mapper);
        assertTimeout(Duration.ofSeconds(2), () -> assertTrue(client.classify("退款", null).isEmpty()));
    }

    @Test
    void timesOutWhenHeadersArriveButResponseBodyStalls() {
        properties.getJev().setTimeout(Duration.ofMillis(250));
        bodyDelayMs = 1200;
        client = new JevIntentClient(properties, mapper);
        assertTimeout(Duration.ofMillis(900), () -> assertTrue(client.classify("退款", null).isEmpty()));
    }
}
