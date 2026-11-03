package com.fluxagent.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ByteArrayResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ModelConfigurationTest {
    @TempDir Path temp;
    private final AtomicInteger localDotenvAccesses = new AtomicInteger();
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context -> context.addProtocolResolver((location, resourceLoader) -> {
                if (!"file:.env".equals(location)) { return null; }
                localDotenvAccesses.incrementAndGet();
                return new ByteArrayResource("DMX_API_KEY=synthetic-local-key\nDEEPSEEK_MODEL=unexpected-local-model\n"
                        .getBytes(StandardCharsets.UTF_8)) {
                    @Override public String getFilename() { return ".env"; }
                };
            }))
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withPropertyValues("spring.config.location=classpath:/application.yml", "ECHOMIND_ENV_FILE=");

    @Test
    void usesDmxDeepSeekAndJevDefaultsWithoutReadingLocalCredentials() {
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(0, localDotenvAccesses.get(), "Tests must not access the working directory .env");
            var env = context.getEnvironment();
            assertEquals("deepseek", env.getProperty("spring.profiles.active"));
            assertEquals("https://www.dmxapi.cn", env.getProperty("spring.ai.deepseek.base-url"));
            assertEquals("deepseek-v4.1-flash-1017", env.getProperty("spring.ai.deepseek.chat.options.model"));
            assertEquals("jev-latest", env.getProperty("echomind.jev.model"));
        });
    }

    @Test
    void loadsDotenvCredentialsForBothModelsAndAllowsOverrides() throws Exception {
        Path dotenv = temp.resolve(".env");
        Files.writeString(dotenv, "DMX_API_KEY=local-test-key\nDEEPSEEK_MODEL=local-model\n");
        var configured = runner.withPropertyValues("ECHOMIND_ENV_FILE=" + dotenv.toUri() + "[.properties]");
        configured.run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(0, localDotenvAccesses.get());
            var env = context.getEnvironment();
            assertEquals("local-test-key", env.getProperty("spring.ai.deepseek.api-key"));
            assertEquals("local-test-key", env.getProperty("echomind.jev.api-key"));
            assertEquals("local-model", env.getProperty("spring.ai.deepseek.chat.options.model"));
        });
        configured.withPropertyValues("DMX_API_KEY=override-test-key", "DEEPSEEK_MODEL=override-model").run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals("override-test-key", context.getEnvironment().getProperty("spring.ai.deepseek.api-key"));
            assertEquals("override-model", context.getEnvironment().getProperty("spring.ai.deepseek.chat.options.model"));
        });
    }
}
