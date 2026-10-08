import { beforeAll, describe, expect, it } from "vitest";
import { ALL_ROWS } from "../support/dataset";
import { INGEST_KEY } from "../support/env";
import { api, forgetTokens, tokenFor } from "../support/http";
import { resetAndSeed, sql } from "../support/seed";

beforeAll(async () => {
  await resetAndSeed();
  forgetTokens();
});

function valid(overrides: Record<string, unknown> = {}) {
  return {
    modelName: "IngestProbe",
    version: "1.0.0",
    category: "Probe",
    subCategory: "Unit",
    confidenceAccuracy: 50,
    sensitivity: 50,
    specificity: 50,
    f1Score: 50,
    recall: 50,
    precision: 50,
    payload: { probe: true, values: [1, 2, 3] },
    ...overrides,
  };
}

function ingest(body: unknown, headers: Record<string, string> = { "X-Ingest-Key": INGEST_KEY }) {
  return api("/results", { method: "POST", headers, json: body });
}

async function countRows(): Promise<number> {
  const [{ count }] = await sql<{ count: string }>("SELECT count(*) FROM results");
  return Number(count);
}

describe("POST /api/results", () => {
  describe("access", () => {
    it("accepts the machine ingest key and stores no creator", async () => {
      const res = await ingest(valid({ version: "key" }));
      expect(res.status).toBe(201);
      expect(res.body).toMatchObject({ modelName: "IngestProbe", version: "key", vectorized: false, createdBy: null });
      expect(res.body.id).toMatch(/^[0-9a-f-]{36}$/);
      expect(res.body.iterationId).toMatch(/^\d{14}$/);
      expect(res.body.sizeBytes).toBeGreaterThan(0);
    });

    it("accepts a superadmin token and records the creator", async () => {
      const res = await ingest(valid({ version: "super" }), { Authorization: `Bearer ${await tokenFor("superadmin")}` });
      expect(res.status).toBe(201);
      expect(res.body.createdBy).toBe("00000000-0000-4000-8000-000000000001");
    });

    it.each(["admin", "member"] as const)("forbids %s", async (role) => {
      const before = await countRows();
      const res = await ingest(valid(), { Authorization: `Bearer ${await tokenFor(role)}` });
      expect(res.status).toBe(403);
      expect(await countRows()).toBe(before);
    });

    it.each([
      ["no credentials", {}],
      ["a wrong ingest key", { "X-Ingest-Key": "wrong-key" }],
      ["an empty ingest key", { "X-Ingest-Key": "" }],
      ["a garbage bearer token", { Authorization: "Bearer nope" }],
    ])("rejects %s with 401", async (_name, headers) => {
      const res = await ingest(valid(), headers as Record<string, string>);
      expect(res.status).toBe(401);
    });
  });

  describe("validation", () => {
    it("accepts boundary metric values and null metrics", async () => {
      const zero = await ingest(valid({ confidenceAccuracy: 0, sensitivity: 100, specificity: 0.0001, f1Score: 99.9999 }));
      expect(zero.status).toBe(201);
      expect(zero.body).toMatchObject({ confidenceAccuracy: 0, sensitivity: 100, specificity: 0.0001, f1Score: 99.9999 });
      const nulls = await ingest(
        valid({ category: null, subCategory: null, confidenceAccuracy: null, sensitivity: null, specificity: null, f1Score: null, recall: null, precision: null }),
      );
      expect(nulls.status).toBe(201);
      expect(nulls.body.category).toBeNull();
      expect(nulls.body.recall).toBeNull();
    });

    it("trims labels", async () => {
      const res = await ingest(valid({ modelName: "  Trimmed  ", version: " 2.0 ", category: " Cat ", subCategory: " Sub " }));
      expect(res.status).toBe(201);
      expect(res.body).toMatchObject({ modelName: "Trimmed", version: "2.0", category: "Cat", subCategory: "Sub" });
    });

    it("stores identical payloads as separate results", async () => {
      const a = await ingest(valid({ version: "dup" }));
      const b = await ingest(valid({ version: "dup" }));
      expect(a.status).toBe(201);
      expect(b.status).toBe(201);
      expect(a.body.id).not.toBe(b.body.id);
      expect(a.body.jsonPath).not.toBe(b.body.jsonPath);
    });

    it.each([
      ["missing modelName", { modelName: undefined }],
      ["blank modelName", { modelName: "   " }],
      ["missing version", { version: undefined }],
      ["missing payload", { payload: undefined }],
      ["null payload", { payload: null }],
      ["modelName over 255 characters", { modelName: "m".repeat(256) }],
      ["version over 100 characters", { version: "v".repeat(101) }],
      ["confidence above 100", { confidenceAccuracy: 100.0001 }],
      ["negative sensitivity", { sensitivity: -0.0001 }],
      ["precision of 1000", { precision: 1000 }],
      ["metric as text", { recall: "high" }],
      ["metric as object", { f1Score: { value: 1 } }],
      ["modelName as number", { modelName: { name: 1 } }],
    ])("rejects %s with 400 and stores nothing", async (_name, overrides) => {
      const before = await countRows();
      const res = await ingest(valid(overrides));
      expect(res.status).toBe(400);
      expect(res.body).toMatchObject({ status: 400, message: expect.any(String) });
      expect(await countRows()).toBe(before);
    });

    it("rejects malformed JSON with 400", async () => {
      const res = await api("/results", {
        method: "POST",
        headers: { "X-Ingest-Key": INGEST_KEY, "Content-Type": "application/json" },
        body: '{"modelName": "x", ',
      });
      expect(res.status).toBe(400);
    });

    it("rejects a non-JSON content type with 415", async () => {
      const res = await api("/results", {
        method: "POST",
        headers: { "X-Ingest-Key": INGEST_KEY, "Content-Type": "text/plain" },
        body: "modelName=x",
      });
      expect(res.status).toBe(415);
    });

    it("accepts a large payload", async () => {
      const big = { rows: Array.from({ length: 20000 }, (_, i) => ({ i, label: `row-${i}`, score: i / 20000 })) };
      const res = await ingest(valid({ version: "big", payload: big }));
      expect(res.status).toBe(201);
      expect(res.body.sizeBytes).toBeGreaterThan(500_000);
      const payload = await api(`/results/${res.body.id}/payload`, { token: await tokenFor("member") });
      expect(payload.body.rows).toHaveLength(20000);
    });
  });

  describe("effects", () => {
    it("makes a new model and category visible in lists and filter options", async () => {
      await ingest(valid({ modelName: "FreshModel", category: "FreshCategory", subCategory: "FreshSub" }));
      const token = await tokenFor("member");
      const options = await api("/results/filter-options?category=FreshCategory", { token });
      expect(options.body.models).toContain("FreshModel");
      expect(options.body.categories).toContain("FreshCategory");
      expect(options.body.subCategories).toEqual(["FreshSub"]);
      const listed = await api("/results?model=FreshModel", { token });
      expect(listed.body.total).toBe(1);
    });

    it("handles concurrent ingests without losing or duplicating rows", async () => {
      const before = await countRows();
      const responses = await Promise.all(
        Array.from({ length: 25 }, (_, i) => ingest(valid({ modelName: "Concurrent", version: `c${i}` }))),
      );
      expect(responses.map((r) => r.status)).toEqual(Array(25).fill(201));
      expect(new Set(responses.map((r) => r.body.id)).size).toBe(25);
      expect(new Set(responses.map((r) => r.body.jsonPath)).size).toBe(25);
      expect(await countRows()).toBe(before + 25);
    });

    it("never changes the seeded rows", async () => {
      const rows = await sql<{ count: string }>("SELECT count(*) FROM results WHERE model_name IN ('BulkModel','CardioNet','NeuroSlice','ChestScan')");
      expect(Number(rows[0].count)).toBe(ALL_ROWS.length);
    });
  });
});

describe("POST /api/results/reindex", () => {
  it("is available to the superadmin only", async () => {
    for (const role of ["admin", "member"] as const) {
      const res = await api("/results/reindex", { method: "POST", token: await tokenFor(role) });
      expect(res.status).toBe(403);
    }
    expect((await api("/results/reindex", { method: "POST" })).status).toBe(401);
  });

  it("marks every result for re-vectorization and reports the count", async () => {
    await sql("UPDATE results SET is_vectorized = TRUE, vectorized_at = NOW()");
    const res = await api("/results/reindex", { method: "POST", token: await tokenFor("superadmin") });
    expect(res.status).toBe(200);
    expect(res.body.reset).toBe(await countRows());
    expect(typeof res.body.message).toBe("string");
    const [{ count }] = await sql<{ count: string }>("SELECT count(*) FROM results WHERE is_vectorized");
    expect(Number(count)).toBe(0);
  });
});
