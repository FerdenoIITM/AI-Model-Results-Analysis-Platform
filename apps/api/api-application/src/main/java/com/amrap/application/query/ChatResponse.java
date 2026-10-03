package com.amrap.application.query;

import java.util.List;

public record ChatResponse(String answer, List<ChatCitation> citations) {
}
