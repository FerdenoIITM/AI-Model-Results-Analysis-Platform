package com.amrap.application.rag;

import java.util.ArrayList;
import java.util.List;

/**
 * Simple sliding-window chunker over flattened text. We split on newlines first so that
 * `key: value` lines stay intact, then group lines up to {@code chunkSize} characters, with
 * {@code chunkOverlap} characters of overlap between consecutive chunks.
 */
public final class TextChunker {

    private TextChunker() {
    }

    public static List<String> chunk(String text, int chunkSize, int chunkOverlap) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        int effectiveSize = Math.max(100, chunkSize);
        int effectiveOverlap = Math.max(0, Math.min(chunkOverlap, effectiveSize / 2));

        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String line : text.split("\n")) {
            if (line.isEmpty()) {
                continue;
            }
            if (current.length() + line.length() + 1 > effectiveSize && current.length() > 0) {
                chunks.add(current.toString().trim());
                current = overlapTail(current, effectiveOverlap);
            }
            current.append(line).append('\n');
        }
        if (current.length() > 0) {
            chunks.add(current.toString().trim());
        }
        return chunks;
    }

    private static StringBuilder overlapTail(StringBuilder previous, int overlap) {
        if (overlap <= 0 || previous.length() <= overlap) {
            return new StringBuilder();
        }
        String tail = previous.substring(previous.length() - overlap);
        return new StringBuilder(tail);
    }
}
