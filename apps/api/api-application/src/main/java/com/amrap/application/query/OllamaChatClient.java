package com.amrap.application.query;

import com.amrap.application.common.AppException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Calls Ollama {@code /api/chat} with an explicit model name (classify / SQL / answer). */
@Component
public class OllamaChatClient {

    private static final Logger log = LoggerFactory.getLogger(OllamaChatClient.class);

    public record ChatMessage(String role, String content) {}

    private final ChatProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public OllamaChatClient(ChatProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public String chat(String model, List<ChatMessage> messages) {
        if (model == null || model.isBlank()) {
            throw new AppException(500, "Ollama model name is required");
        }
        String base = properties.getOllamaBaseUrl();
        if (base == null || base.isBlank()) {
            throw new AppException(503, "OLLAMA_BASE_URL is not configured");
        }
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }

        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("stream", false);
        ObjectNode options = body.putObject("options");
        options.put("temperature", 0.2);
        ArrayNode msgs = body.putArray("messages");
        for (ChatMessage m : messages) {
            ObjectNode node = msgs.addObject();
            node.put("role", m.role());
            node.put("content", m.content());
        }

        try {
            byte[] payload = mapper.writeValueAsBytes(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/api/chat"))
                    .timeout(Duration.ofMillis(Math.max(5_000, properties.getOllamaTimeoutMs())))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                log.error("Ollama {} returned {}: {}", model, response.statusCode(), response.body());
                throw new AppException(502, "Ollama call failed (" + response.statusCode() + ")");
            }
            JsonNode root = mapper.readTree(response.body());
            JsonNode content = root.path("message").path("content");
            if (content.isMissingNode() || content.asText().isBlank()) {
                throw new AppException(502, "Ollama returned an empty response");
            }
            return content.asText().trim();
        } catch (AppException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Ollama call to model {} failed", model, ex);
            throw new AppException(502, "Ollama call failed: " + ex.getMessage());
        }
    }
}
