# API overview

Source of truth: [`packages/openapi/openapi.yaml`](../packages/openapi/openapi.yaml).

## Auth

| Method | Path | Auth | Notes |
|--------|------|------|-------|
| POST | `/api/auth/login` | No | Returns JWT + user |
| GET | `/api/auth/me` | Bearer | Current user |

## Users

| Method | Path | Auth | Notes |
|--------|------|------|-------|
| GET | `/api/users` | Bearer | Superadmin: all; Admin: self + created members; Member: 403 |
| POST | `/api/users` | Bearer | Superadmin: admin/member; Admin: member only |
| PATCH | `/api/users/{id}` | Bearer | Ownership rules; role change = superadmin only |
| POST | `/api/users/{id}/deactivate` | Bearer | Soft deactivate |

Implementation: `apps/api` (Java Spring Boot).
