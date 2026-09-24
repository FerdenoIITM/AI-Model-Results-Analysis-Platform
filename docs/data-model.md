# Data model

Schema migrations live in [`infra/db/migrations/`](../infra/db/migrations/). Domain types will live in `apps/api/api-domain` when the Java layer is implemented.

## Current schema (V1)

### Entity: User

Application identity for authentication and role-based authorization.

| Column | Type | Notes |
|--------|------|--------|
| `id` | `UUID` | Primary key |
| `email` | `VARCHAR(320)` | Unique login identifier; stored lowercase |
| `password_hash` | `VARCHAR(255)` | Bcrypt/Argon2 hash only (hashed in the app) |
| `role` | `user_role` ENUM | `superadmin` \| `admin` \| `member` |
| `display_name` | `VARCHAR(120)` | Display label in UI |
| `created_by` | `UUID` FK → `users.id` | Creator; `NULL` for bootstrap Superadmin(s) |
| `is_active` | `BOOLEAN` | Soft deactivation; inactive users cannot log in |
| `last_login_at` | `TIMESTAMPTZ` | Last successful login |
| `failed_login_attempts` | `INTEGER` | Brute-force counter |
| `locked_until` | `TIMESTAMPTZ` | Optional temporary lockout |
| `password_changed_at` | `TIMESTAMPTZ` | For future password-policy checks |
| `created_at` / `updated_at` | `TIMESTAMPTZ` | Audit; `updated_at` via trigger |

### Roles

| Role | Meaning |
|------|---------|
| `superadmin` | Full access; manage all users; created at setup (not via normal UI) |
| `admin` | Create/manage only Members they created (`created_by = self`) |
| `member` | Read-only; no User Management |

### Ownership (`created_by`)

```text
Superadmin (created_by NULL)
   ├── Admin A
   │      ├── Member A1
   │      └── Member A2
   └── Admin B
          ├── Member B1
          └── Member B2
```

- Backend authorization uses `role` + `created_by` (not UI alone).
- Admin A manages rows where `created_by = Admin A.id` and `role = 'member'`.
- Superadmin may manage every row.
- Prefer `is_active = FALSE` over `DELETE`; FK is `ON DELETE RESTRICT` so creators with dependents cannot be hard-deleted.

### Authentication vs authorization

- **Authentication:** email + `password_hash` (+ `is_active` / lockout fields).
- **Authorization:** `role` and ownership via `created_by`.

## Planned later (not in V1)

| Entity | Purpose |
|--------|---------|
| Model | Registered AI/ML model metadata |
| Experiment | A run/configuration of a model |
| Result | Prediction or evaluation output |
| Metric | Metrics attached to a result |

## Bootstrap

See [`infra/db/seeds/README.md`](../infra/db/seeds/README.md) for creating the first Superadmin securely.
