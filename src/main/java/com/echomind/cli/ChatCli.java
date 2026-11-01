package com.echomind.cli;

import com.echomind.api.dto.ChatRequest;
import com.echomind.chat.ChatService;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.util.UUID;

@Component
public class ChatCli {
    private final ChatService chatService;

    public ChatCli(ChatService chatService) { this.chatService = chatService; }

    public void run(Reader input, PrintWriter output, String userId, String initialConversationId) throws IOException {
        String conversationId = initialConversationId == null || initialConversationId.isBlank()
                ? UUID.randomUUID().toString() : initialConversationId;
        BufferedReader reader = input instanceof BufferedReader buffered ? buffered : new BufferedReader(input);
        output.println("FluxAgent 命令行客服");
        help(output);
        while (!Thread.currentThread().isInterrupted()) {
            output.print("你 > ");
            output.flush();
            String line = reader.readLine();
            if (line == null) { break; }
            String message = line.trim();
            if (message.isEmpty()) { continue; }
            if (message.equals("/exit") || message.equals("/quit")) { break; }
            if (message.equals("/help")) {
                help(output);
            } else if (message.equals("/new")) {
                conversationId = UUID.randomUUID().toString();
                output.println("已开始新会话：" + conversationId);
            } else if (message.startsWith("/")) {
                output.println("未知命令。输入 /help 查看帮助。");
            } else {
                try {
                    var response = chatService.chat(new ChatRequest(message, userId, conversationId));
                    conversationId = response.conversationId();
                    output.println("FluxAgent > " + response.response());
                    if (response.escalated()) { output.println("[建议转人工处理]"); }
                } catch (RuntimeException ex) {
                    output.println("本次请求失败：" + ex.getMessage() + "。可以继续输入重试。");
                }
            }
            output.flush();
        }
        output.println("会话已结束。");
        output.flush();
    }

    private void help(PrintWriter output) {
        output.println("直接输入消息开始对话；/new 新会话；/help 帮助；/exit 退出。");
    }
}
