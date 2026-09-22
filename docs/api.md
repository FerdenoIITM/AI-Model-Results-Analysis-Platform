# API overview

Source of truth for request/response shapes: [`packages/openapi/openapi.yaml`](../packages/openapi/openapi.yaml).

Planned endpoint groups (to be implemented):

- Auth — login / token refresh
- Models — register and list AI/ML models
- Results — submit, list, filter experiment/prediction results
- Analytics — compare models and track metrics over time
- LLM — natural-language query over stored results

Implementation lives under `apps/api` (Java Spring Boot).
