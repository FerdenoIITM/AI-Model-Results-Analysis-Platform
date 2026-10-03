package com.amrap.application.query;

import java.util.List;

/**
 * Natural-language query input.
 *
 * <p>Optional filters narrow the vector search before similarity scoring.
 *
 * @param question user text
 * @param modelName optional exact-match filter on {@code model_name}
 * @param version optional exact-match filter on {@code version}
 * @param iterationId optional exact-match filter on {@code iteration_id}
 * @param fromIso optional ISO-8601 lower bound on {@code inserted_at}
 * @param toIso optional ISO-8601 upper bound on {@code inserted_at}
 * @param history optional earlier turns, oldest first, not including {@code question}
 */
public record ChatRequest(
        String question,
        String modelName,
        String version,
        String iterationId,
        String fromIso,
        String toIso,
        List<ChatTurn> history) {
}
