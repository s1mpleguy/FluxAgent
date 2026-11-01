package com.echomind.cli;

import com.echomind.chat.ChatService;
import com.echomind.api.dto.ChatRequest;
import com.echomind.api.dto.ChatResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ChatCliTest {
    private final ChatService service = mock(ChatService.class);
    private final ChatCli cli = new ChatCli(service);

    @Test
    void keepsConversationUntilNewCommand() throws Exception {
        when(service.chat(any())).thenAnswer(invocation -> response(invocation.getArgument(0)));
        StringWriter output = new StringWriter();
        cli.run(new StringReader("你好\n退款\n/new\n登录问题\n/exit\n不应处理\n"), new PrintWriter(output), "alice", "initial");
        ArgumentCaptor<ChatRequest> requests = ArgumentCaptor.forClass(ChatRequest.class);
        verify(service, times(3)).chat(requests.capture());
        List<ChatRequest> values = requests.getAllValues();
        assertThat(values).extracting(ChatRequest::userId).containsOnly("alice");
        assertThat(values.get(0).conversationId()).isEqualTo("initial");
        assertThat(values.get(1).conversationId()).isEqualTo("initial");
        assertThat(values.get(2).conversationId()).isNotBlank().isNotEqualTo("initial");
        assertThat(output.toString()).contains("FluxAgent", "回复");
    }

    @Test
    void helpBlankLinesAndEofDoNotCallChat() throws Exception {
        StringWriter output = new StringWriter();
        cli.run(new StringReader("\n/help\n/unknown\n"), new PrintWriter(output), null, null);
        verifyNoInteractions(service);
        assertThat(output.toString()).contains("/new", "/exit");
    }

    @Test
    void continuesAfterSingleChatFailure() throws Exception {
        when(service.chat(any())).thenThrow(new IllegalStateException("temporarily unavailable"))
                .thenAnswer(invocation -> response(invocation.getArgument(0)));
        StringWriter output = new StringWriter();
        cli.run(new StringReader("你好\n再试一次\n"), new PrintWriter(output), null, null);
        verify(service, times(2)).chat(any());
        assertThat(output.toString()).contains("temporarily unavailable", "回复");
    }

    private ChatResponse response(ChatRequest request) {
        return new ChatResponse(request.conversationId(), "回复", "other", "general", "general",
                List.of("general"), "general", List.of(), "", 1, false, 1, false, true, false, Map.of(), 1, Map.of());
    }
}
