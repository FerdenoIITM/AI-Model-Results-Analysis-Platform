import { http, HttpResponse } from "msw";
import { setupServer } from "msw/node";
import type { ResultSummary, UserRole, UserSummary } from "../shared/api/types";
import { PASSWORD, makeResult, seedResults, seedUsers } from "./fixtures";

export const API = "http://api.test/api";

const METRICS = ["confidenceAccuracy", "sensitivity", "specificity", "f1Score", "recall", "precision"] as const;

/** In-memory backend state the handlers read and mutate; reset after every test. */
export const db = {
  users: seedUsers(),
  results: seedResults(),
  /** Every GET /results request URL, in order. */
  resultRequests: [] as URL[],
  filterOptionRequests: [] as URL[],
  chatRequests: [] as unknown[],
  createdResults: [] as unknown[],
  userWrites: [] as { method: string; id?: string; body?: unknown }[],
};

export function resetDb() {
  db.users = seedUsers();
  db.results = seedResults();
  db.resultRequests = [];
  db.filterOptionRequests = [];
  db.chatRequests = [];
  db.createdResults = [];
  db.userWrites = [];
}

export function tokenFor(id: string): string {
  return `test-token-${id}`;
}

export function lastResultsQuery(): URLSearchParams {
  const last = db.resultRequests[db.resultRequests.length - 1];
  if (!last) throw new Error("No GET /results request was made");
  return last.searchParams;
}

function error(status: number, message: string) {
  return HttpResponse.json({ timestamp: new Date().toISOString(), status, message }, { status });
}

function currentUser(request: Request): UserSummary | null {
  const header = request.headers.get("Authorization") ?? "";
  const token = header.startsWith("Bearer ") ? header.slice(7) : "";
  return db.users.find((u) => tokenFor(u.id) === token && u.active) ?? null;
}

function compare(a: unknown, b: unknown): number {
  if (a == null && b == null) return 0;
  if (a == null) return 1;
  if (b == null) return -1;
  if (typeof a === "number" && typeof b === "number") return a - b;
  return String(a).localeCompare(String(b));
}

function searchResults(params: URLSearchParams): ResultSummary[] | string {
  const models = params.getAll("model");
  const categories = params.getAll("category");
  const subCategories = params.getAll("subCategory");
  const vectorized = params.get("vectorized");
  let rows = db.results.filter(
    (r) =>
      (models.length === 0 || models.includes(r.modelName)) &&
      (categories.length === 0 || (r.category != null && categories.includes(r.category))) &&
      (subCategories.length === 0 || (r.subCategory != null && subCategories.includes(r.subCategory))) &&
      (vectorized == null || String(r.vectorized) === vectorized),
  );
  for (const key of METRICS) {
    const ops: [string, (v: number, x: number) => boolean][] = [
      ["Eq", (v, x) => v === x],
      ["Gt", (v, x) => v > x],
      ["Gte", (v, x) => v >= x],
      ["Lt", (v, x) => v < x],
      ["Lte", (v, x) => v <= x],
    ];
    for (const [suffix, test] of ops) {
      const raw = params.get(`${key}${suffix}`);
      if (raw == null) continue;
      const x = Number(raw);
      if (!Number.isFinite(x) || x < 0 || x > 100) return `${key}${suffix} must be between 0 and 100`;
      rows = rows.filter((r) => r[key] != null && test(r[key] as number, x));
    }
  }
  const sort = params.get("sort") ?? "-insertedAt";
  const desc = sort.startsWith("-");
  const field = (desc ? sort.slice(1) : sort) as keyof ResultSummary;
  rows = [...rows].sort((a, b) => {
    const av = a[field];
    const bv = b[field];
    if (av == null || bv == null) return compare(av, bv);
    return desc ? compare(bv, av) : compare(av, bv);
  });
  return rows;
}

export const handlers = [
  http.post(`${API}/auth/login`, async ({ request }) => {
    const body = (await request.json()) as { email?: string; password?: string };
    const found = db.users.find((u) => u.email.toLowerCase() === String(body.email ?? "").toLowerCase());
    if (!found || body.password !== PASSWORD) return error(401, "Invalid email or password");
    if (!found.active) return error(403, "Account is deactivated");
    return HttpResponse.json({ accessToken: tokenFor(found.id), user: found });
  }),

  http.get(`${API}/auth/me`, ({ request }) => {
    const me = currentUser(request);
    return me ? HttpResponse.json(me) : error(401, "Authentication required");
  }),

  http.get(`${API}/results/filter-options`, ({ request }) => {
    if (!currentUser(request)) return error(401, "Authentication required");
    const url = new URL(request.url);
    db.filterOptionRequests.push(url);
    const categories = url.searchParams.getAll("category");
    const distinct = (values: (string | null)[]) =>
      [...new Set(values.filter((v): v is string => v != null))].sort((a, b) => a.localeCompare(b));
    return HttpResponse.json({
      models: distinct(db.results.map((r) => r.modelName)),
      categories: distinct(db.results.map((r) => r.category)),
      subCategories: distinct(
        db.results
          .filter((r) => categories.length === 0 || (r.category != null && categories.includes(r.category)))
          .map((r) => r.subCategory),
      ),
    });
  }),

  http.get(`${API}/results/:id/payload`, ({ request, params }) => {
    if (!currentUser(request)) return error(401, "Authentication required");
    const row = db.results.find((r) => r.id === params.id);
    if (!row) return error(404, "Result not found");
    return new HttpResponse(JSON.stringify({ id: row.id, model: row.modelName, scores: [1, 2] }), {
      headers: { "Content-Type": "application/json" },
    });
  }),

  http.get(`${API}/results`, ({ request }) => {
    if (!currentUser(request)) return error(401, "Authentication required");
    const url = new URL(request.url);
    db.resultRequests.push(url);
    const rows = searchResults(url.searchParams);
    if (typeof rows === "string") return error(400, rows);
    const page = Number(url.searchParams.get("page") ?? 1);
    const pageSize = Number(url.searchParams.get("pageSize") ?? 50);
    return HttpResponse.json({
      results: rows.slice((page - 1) * pageSize, page * pageSize),
      total: rows.length,
      page,
      pageSize,
    });
  }),

  http.post(`${API}/results/reindex`, ({ request }) => {
    const me = currentUser(request);
    if (!me) return error(401, "Authentication required");
    if (me.role !== "superadmin") return error(403, "Forbidden");
    db.results = db.results.map((r) => ({ ...r, vectorized: false }));
    return HttpResponse.json({ reset: db.results.length, message: "Vector store cleared" });
  }),

  http.post(`${API}/results`, async ({ request }) => {
    const me = currentUser(request);
    if (!me) return error(401, "Authentication required");
    if (me.role !== "superadmin") return error(403, "Only superadmin can submit results");
    const body = (await request.json()) as Partial<ResultSummary> & { payload?: unknown };
    db.createdResults.push(body);
    if (!body.modelName) return error(400, "modelName is required");
    const created = makeResult({ ...body, id: `r-new-${db.createdResults.length}`, vectorized: false } as Partial<ResultSummary>);
    db.results = [...db.results, created];
    return HttpResponse.json(created, { status: 201 });
  }),

  http.get(`${API}/users`, ({ request }) => {
    const me = currentUser(request);
    if (!me) return error(401, "Authentication required");
    if (me.role === "member") return error(403, "Forbidden");
    const manageable = me.role === "superadmin" ? db.users : db.users.filter((u) => u.createdBy === me.id);
    return HttpResponse.json(manageable.filter((u) => u.id !== me.id));
  }),

  http.post(`${API}/users`, async ({ request }) => {
    const me = currentUser(request);
    if (!me) return error(401, "Authentication required");
    const body = (await request.json()) as { email: string; password: string; displayName: string; role: UserRole };
    db.userWrites.push({ method: "POST", body });
    if (db.users.some((u) => u.email.toLowerCase() === body.email.toLowerCase())) {
      return error(409, "Email already exists");
    }
    const created: UserSummary = {
      id: `u-new-${db.users.length}`,
      email: body.email.toLowerCase(),
      displayName: body.displayName,
      role: body.role,
      createdBy: me.id,
      active: true,
      lastLoginAt: null,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    };
    db.users = [...db.users, created];
    return HttpResponse.json(created, { status: 201 });
  }),

  http.patch(`${API}/users/:id`, async ({ request, params }) => {
    const me = currentUser(request);
    if (!me) return error(401, "Authentication required");
    const body = (await request.json()) as Partial<UserSummary> & { password?: string };
    db.userWrites.push({ method: "PATCH", id: String(params.id), body });
    const target = db.users.find((u) => u.id === params.id);
    if (!target) return error(404, "User not found");
    const { password: _password, ...changes } = body;
    const updated = { ...target, ...changes };
    db.users = db.users.map((u) => (u.id === target.id ? updated : u));
    return HttpResponse.json(updated);
  }),

  http.delete(`${API}/users/:id`, ({ request, params }) => {
    const me = currentUser(request);
    if (!me) return error(401, "Authentication required");
    db.userWrites.push({ method: "DELETE", id: String(params.id) });
    const target = db.users.find((u) => u.id === params.id);
    if (!target) return error(404, "User not found");
    db.users = db.users
      .filter((u) => u.id !== target.id)
      .map((u) => (u.createdBy === target.id ? { ...u, createdBy: me.id } : u));
    return new HttpResponse(null, { status: 204 });
  }),

  http.post(`${API}/chat`, async ({ request }) => {
    if (!currentUser(request)) return error(401, "Authentication required");
    const body = await request.json();
    db.chatRequests.push(body);
    return HttpResponse.json({
      answer: "CardioNet 1.0.0 had the highest confidence (97.5).",
      citations: [
        {
          resultId: "r-0001",
          modelName: "CardioNet",
          version: "1.0.0",
          iterationId: "20260101000001",
          insertedAt: "2026-01-01T00:01:00Z",
          chunkIndex: 0,
          score: 0.91234,
        },
      ],
    });
  }),
];

export const server = setupServer(...handlers);
