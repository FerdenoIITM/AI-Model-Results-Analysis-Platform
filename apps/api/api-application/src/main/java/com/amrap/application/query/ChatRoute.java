package com.amrap.application.query;

/** Classification result for the chat pipeline. */
public enum ChatRoute {
    NONE,
    SQL,
    RAG,
    HYBRID;

    public static ChatRoute parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return RAG;
        }
        try {
            return ChatRoute.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return RAG;
        }
    }
}
