package com.echomind;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import com.echomind.cli.ChatCli;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

@SpringBootApplication
public class EchoMindJavaApplication {

    public static void main(String[] args) throws IOException {
        var arguments = new DefaultApplicationArguments(args);
        SpringApplication application = new SpringApplication(EchoMindJavaApplication.class);
        if (arguments.containsOption("cli") && !arguments.getOptionValues("cli").contains("false")) {
            application.setWebApplicationType(WebApplicationType.NONE);
            try (var context = application.run(args)) {
                context.getBean(ChatCli.class).run(new InputStreamReader(System.in, StandardCharsets.UTF_8),
                        new PrintWriter(System.out, true, StandardCharsets.UTF_8),
                        option(arguments, "user-id"), option(arguments, "conversation-id"));
            }
        } else {
            application.run(args);
        }
    }

    private static String option(DefaultApplicationArguments arguments, String name) {
        var values = arguments.getOptionValues(name);
        return values == null || values.isEmpty() ? null : values.getFirst();
    }
}
