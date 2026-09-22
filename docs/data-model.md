# Data model (draft)

Conceptual entities for multi-model results storage:

| Entity | Purpose |
|--------|---------|
| User | Dashboard authentication and access control |
| Model | Registered AI/ML model metadata |
| Experiment | A run/configuration of a model |
| Result | Prediction or evaluation output payload |
| Metric | Named numeric/categorical metrics attached to a result |

Schema migrations live in `infra/db/migrations/`. Domain types live in `apps/api/api-domain`.

This document will be updated when the first Flyway migration is defined.
