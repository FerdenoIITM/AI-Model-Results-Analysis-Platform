package com.amrap.application.query;

import org.springframework.stereotype.Service;

/**
 * Facade kept for {@code ChatController} and existing wiring. Delegates to
 * {@link ChatOrchestrator}.
 */
@Service
public class ChatService {

    private final ChatOrchestrator orchestrator;

    public ChatService(ChatOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    public ChatResponse ask(ChatRequest request) {
        return orchestrator.ask(request);
    }
}
