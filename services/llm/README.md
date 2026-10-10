# services/llm

Reserved slot for a standalone LLM worker.

**Current design:** natural-language querying lives in `apps/api/api-llm` (Spring AI) so Java remains the primary backend for the course.

**When to use this folder:** extract the LLM worker here if Spring AI grows too large, or if a Python/LangChain service is required later — without restructuring `apps/api` or `apps/web`.

No image or compose service exists for it yet; add an `infra/docker/llm.Dockerfile` and a service in `docker-compose.yml` when it is extracted.
