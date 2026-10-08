package com.amrap.application.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class TextChunkerTest {

    @Test
    void emptyOrNullTextGivesNoChunks() {
        assertThat(TextChunker.chunk(null, 800, 100)).isEmpty();
        assertThat(TextChunker.chunk("", 800, 100)).isEmpty();
        assertThat(TextChunker.chunk(" \n ", 800, 100)).isEmpty();
    }

    @Test
    void shortTextIsOneChunk() {
        assertThat(TextChunker.chunk("a: 1\nb: 2\n", 800, 100)).containsExactly("a: 1\nb: 2");
    }

    @Test
    void longTextSplitsOnLineBoundariesWithinSize() {
        String text = lines(100);

        List<String> chunks = TextChunker.chunk(text, 200, 0);

        assertThat(chunks).hasSizeGreaterThan(1);
        chunks.forEach(c -> assertThat(c.length()).isLessThanOrEqualTo(200));
        assertThat(String.join("\n", chunks)).isEqualTo(text.trim());
    }

    @Test
    void consecutiveChunksOverlap() {
        List<String> chunks = TextChunker.chunk(lines(100), 200, 50);

        for (int i = 1; i < chunks.size(); i++) {
            String previousTail = chunks.get(i - 1).substring(chunks.get(i - 1).length() - 20);
            assertThat(chunks.get(i)).contains(previousTail);
        }
    }

    @Test
    void sizeBelowMinimumIsClampedTo100AndOverlapToHalf() {
        List<String> chunks = TextChunker.chunk(lines(50), 10, 500);

        chunks.forEach(c -> assertThat(c.length()).isLessThanOrEqualTo(100));
        assertThat(chunks).hasSizeGreaterThan(1);
    }

    @Test
    void singleLineLongerThanChunkIsKeptWhole() {
        String line = "x".repeat(5000);

        assertThat(TextChunker.chunk(line, 800, 100)).containsExactly(line);
    }

    private static String lines(int count) {
        return IntStream.range(0, count).mapToObj(i -> "key" + i + ": value" + i).collect(Collectors.joining("\n")) + "\n";
    }
}
