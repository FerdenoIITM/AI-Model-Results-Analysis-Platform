package com.amrap.web.llm;

import com.amrap.application.query.ChatRequest;
import com.amrap.application.query.ChatResponse;
import com.amrap.application.query.ChatService;
import com.amrap.security.SecurityUtils;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping
    public ResponseEntity<ChatResponse> ask(@Valid @RequestBody ChatRequestBody body) {
        SecurityUtils.requireCurrentUser();
        ChatResponse response = chatService.ask(new ChatRequest(
                body.question(),
                body.modelName(),
                body.version(),
                body.iterationId(),
                body.fromIso(),
                body.toIso(),
                body.history()));
        return ResponseEntity.ok(response);
    }
}
