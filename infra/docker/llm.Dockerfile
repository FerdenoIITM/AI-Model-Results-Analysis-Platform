# Optional standalone LLM worker image (scaffold)
# Only used if services/llm is extracted from apps/api/api-llm
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
CMD ["echo", "llm image scaffold — not activated; LLM lives in apps/api/api-llm for now"]
