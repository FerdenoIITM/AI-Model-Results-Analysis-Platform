package com.amrap.application.rag;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Iterator;
import java.util.Map;

/**
 * Flattens arbitrary JSON into "path: value" lines so we can split on text boundaries. Nested
 * objects become dot-separated keys; arrays include the numeric index.
 *
 * <pre>
 * { "model": { "name": "M1" }, "scores": [0.9, 0.8] }
 *  →
 * model.name: M1
 * scores[0]: 0.9
 * scores[1]: 0.8
 * </pre>
 */
public final class JsonFlattener {

    private JsonFlattener() {
    }

    public static String flatten(JsonNode node) {
        StringBuilder sb = new StringBuilder();
        walk("", node, sb);
        return sb.toString();
    }

    private static void walk(String prefix, JsonNode node, StringBuilder sb) {
        if (node == null || node.isNull()) {
            append(prefix, "null", sb);
            return;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            if (!fields.hasNext()) {
                append(prefix, "{}", sb);
                return;
            }
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String nextPrefix = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
                walk(nextPrefix, entry.getValue(), sb);
            }
            return;
        }
        if (node.isArray()) {
            if (node.isEmpty()) {
                append(prefix, "[]", sb);
                return;
            }
            for (int i = 0; i < node.size(); i++) {
                walk(prefix + "[" + i + "]", node.get(i), sb);
            }
            return;
        }
        // scalar
        append(prefix, node.asText(), sb);
    }

    private static void append(String key, String value, StringBuilder sb) {
        if (key.isEmpty()) {
            sb.append(value);
        } else {
            sb.append(key).append(": ").append(value);
        }
        sb.append('\n');
    }
}
