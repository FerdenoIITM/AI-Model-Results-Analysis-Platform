import { beforeAll, describe, expect, it } from "vitest";
import { ALL_ROWS, FLOW_ROWS, expectedCount, type SeedResult } from "../support/dataset";
import { api, forgetTokens, tokenFor } from "../support/http";
import { resetAndSeed, sql } from "../support/seed";

let token: string;

beforeAll(async () => {
  await resetAndSeed();
  forgetTokens();
  token = await tokenFor("member");
});

function list(query: string) {
  return api(`/results${query ? `?${query}` : ""}`, { token });
}

async function listAll(query: string): Promise<any[]> {
  const res = await list(`${query}${query ? "&" : ""}pageSize=100`);
  expect(res.status).toBe(200);
  const rows = [...res.body.results];
  const pages = Math.ceil(res.body.total / 100);
  for (let page = 2; page <= pages; page++) {
    const next = await list(`${query}${query ? "&" : ""}pageSize=100&page=${page}`);
    rows.push(...next.body.results);
  }
  return rows;
}

const TOTAL = ALL_ROWS.length;

describe("GET /api/results", () => {
  it("returns the first page with the documented shape, newest first", async () => {
    const res = await list("");
    expect(res.status).toBe(200);
    expect(res.body).toMatchObject({ total: TOTAL, page: 1, pageSize: 50 });
    expect(res.body.results).toHaveLength(50);
    const first = res.body.results[0];
    expect(Object.keys(first).sort()).toEqual(
      [
        "category", "confidenceAccuracy", "createdBy", "f1Score", "id", "insertedAt", "iterationId", "jsonPath",
        "modelName", "precision", "recall", "sensitivity", "sizeBytes", "specificity", "subCategory", "vectorized",
        "vectorizedAt", "version",
      ].sort(),
    );
    expect(first).toMatchObject({ modelName: "ChestScan", version: "3.1.0", vectorized: false, createdBy: null });
    expect(first.iterationId).toMatch(/^\d{14}$/);
    const times = res.body.results.map((r: any) => new Date(r.insertedAt).getTime());
    expect(times).toEqual([...times].sort((a, b) => b - a));
  });

  it.each(["superadmin", "admin", "member"] as const)("can be read by %s", async (role) => {
    const res = await api("/results?pageSize=1", { token: await tokenFor(role) });
    expect(res.status).toBe(200);
    expect(res.body.total).toBe(TOTAL);
  });

  it("requires authentication", async () => {
    const res = await api("/results");
    expect(res.status).toBe(401);
    expect(res.body.message).toBe("Authentication required");
  });

  describe("filters", () => {
    it("filters by one or several models", async () => {
      expect((await list("model=NeuroSlice")).body.total).toBe(expectedCount((r) => r.modelName === "NeuroSlice"));
      expect((await list("model=NeuroSlice&model=ChestScan")).body.total).toBe(
        expectedCount((r) => ["NeuroSlice", "ChestScan"].includes(r.modelName)),
      );
    });

    it("filters by category and sub category", async () => {
      const rows = await listAll("category=Cardiology&subCategory=Aortic%20Valve");
      expect(rows.length).toBe(expectedCount((r) => r.category === "Cardiology" && r.subCategory === "Aortic Valve"));
      expect(new Set(rows.map((r) => `${r.category}/${r.subCategory}`))).toEqual(new Set(["Cardiology/Aortic Valve"]));
    });

    it("returns an empty page with total 0 when nothing matches", async () => {
      const res = await list("model=ChestScan&category=Cardiology");
      expect(res.status).toBe(200);
      expect(res.body).toMatchObject({ results: [], total: 0, page: 1 });
    });

    it("treats an unknown model as no match rather than an error", async () => {
      const res = await list("model=DoesNotExist");
      expect(res.status).toBe(200);
      expect(res.body.total).toBe(0);
    });

    const OPS: [string, (v: number) => boolean][] = [
      ["Eq", (v) => v === 95],
      ["Gt", (v) => v > 95],
      ["Gte", (v) => v >= 95],
      ["Lt", (v) => v < 95],
      ["Lte", (v) => v <= 95],
    ];

    it.each(OPS)("applies confidenceAccuracy%s=95 and never matches NULL", async (suffix, test) => {
      const rows = await listAll(`confidenceAccuracy${suffix}=95`);
      expect(rows.length).toBe(expectedCount((r) => r.confidenceAccuracy != null && test(r.confidenceAccuracy)));
      expect(rows.every((r) => r.confidenceAccuracy != null && test(r.confidenceAccuracy))).toBe(true);
    });

    it.each([
      ["confidenceAccuracyGte=90&confidenceAccuracyLte=98", (v: number) => v >= 90 && v <= 98],
      ["confidenceAccuracyGt=90&confidenceAccuracyLt=98", (v: number) => v > 90 && v < 98],
      ["confidenceAccuracyGte=95&confidenceAccuracyLte=95", (v: number) => v === 95],
      ["confidenceAccuracyGte=0&confidenceAccuracyLte=100", () => true],
    ])("applies the range %s", async (query, test) => {
      const res = await list(`${query}&pageSize=1`);
      expect(res.status).toBe(200);
      expect(res.body.total).toBe(expectedCount((r) => r.confidenceAccuracy != null && test(r.confidenceAccuracy)));
    });

    it("combines model, category and several metric filters with AND", async () => {
      const rows = await listAll("model=CardioNet&category=Cardiology&confidenceAccuracyGte=95&sensitivityGt=50");
      const expected = FLOW_ROWS.filter(
        (r) =>
          r.modelName === "CardioNet" &&
          r.category === "Cardiology" &&
          (r.confidenceAccuracy ?? -1) >= 95 &&
          (r.sensitivity ?? -1) > 50,
      );
      expect(rows.map((r) => r.version).sort()).toEqual(expected.map((r) => r.version).sort());
    });

    it("filters by vectorized state", async () => {
      expect((await list("vectorized=false")).body.total).toBe(TOTAL);
      expect((await list("vectorized=true")).body.total).toBe(0);
    });

    it.each([
      ["inverted range", "confidenceAccuracyGte=98&confidenceAccuracyLte=90"],
      ["exclusive empty range", "confidenceAccuracyGt=90&confidenceAccuracyLt=90"],
      ["value above 100", "recallGte=100.0001"],
      ["negative value", "precisionLte=-1"],
      ["non-numeric value", "f1ScoreEq=abc"],
      ["empty value", "sensitivityGt="],
      ["infinite value", "specificityGt=Infinity"],
      ["two lower bounds", "confidenceAccuracyGt=10&confidenceAccuracyGte=20"],
      ["equals with a bound", "confidenceAccuracyEq=50&confidenceAccuracyGt=10"],
      ["unknown sort field", "sort=password_hash"],
      ["SQL in sort", "sort=modelName;DROP TABLE results"],
      ["page zero", "page=0"],
      ["negative page", "page=-1"],
      ["non-numeric page", "page=two"],
      ["page size zero", "pageSize=0"],
      ["page size above the maximum", "pageSize=101"],
      ["invalid vectorized flag", "vectorized=maybe"],
      ["removed version filter", "version=1.0.0"],
    ])("rejects an %s with 400", async (_name, query) => {
      const res = await list(query);
      expect(res.status).toBe(400);
      expect(res.body).toMatchObject({ status: 400, message: expect.any(String) });
    });

    it("leaves the data untouched after a hostile sort value", async () => {
      await list("sort=modelName;DROP TABLE results");
      const [{ count }] = await sql<{ count: string }>("SELECT count(*) FROM results");
      expect(Number(count)).toBe(TOTAL);
    });
  });

  describe("sorting", () => {
    function cardioValues(rows: any[], key: keyof SeedResult) {
      return rows.map((r) => r[key]);
    }

    it("sorts metrics numerically with NULL last in both directions", async () => {
      const asc = await listAll("model=CardioNet&category=Cardiology&sort=confidenceAccuracy");
      expect(cardioValues(asc, "confidenceAccuracy")).toEqual([9.5, 94.99, 95, 97, 99.5, 100, null]);
      const desc = await listAll("model=CardioNet&category=Cardiology&sort=-confidenceAccuracy");
      expect(cardioValues(desc, "confidenceAccuracy")).toEqual([100, 99.5, 97, 95, 94.99, 9.5, null]);
    });

    it("sorts another metric column the same way", async () => {
      const asc = await listAll("model=CardioNet&category=Cardiology&sort=sensitivity");
      expect(cardioValues(asc, "sensitivity")).toEqual([0, 9.5, 50, 92, 99, 100, null]);
    });

    it.each(["modelName", "version", "category", "subCategory", "iterationId", "insertedAt"])(
      "sorts by %s in both directions",
      async (field) => {
        const asc = (await list(`sort=${field}&pageSize=100`)).body.results.map((r: any) => r[field]);
        const desc = (await list(`sort=-${field}&pageSize=100`)).body.results.map((r: any) => r[field]);
        const cmp = (a: string, b: string) => (a < b ? -1 : a > b ? 1 : 0);
        expect(asc.every((v: string, i: number) => i === 0 || cmp(asc[i - 1], v) <= 0)).toBe(true);
        expect(desc.every((v: string, i: number) => i === 0 || cmp(desc[i - 1], v) >= 0)).toBe(true);
      },
    );

    it("keeps filters while sorting", async () => {
      const rows = await listAll("model=NeuroSlice&sort=-confidenceAccuracy");
      expect(rows.map((r) => r.confidenceAccuracy)).toEqual([99, 96, 90]);
    });
  });

  describe("pagination", () => {
    it("covers every row exactly once across pages", async () => {
      const ids: string[] = [];
      for (let page = 1; page <= Math.ceil(TOTAL / 25); page++) {
        const res = await list(`pageSize=25&page=${page}&sort=confidenceAccuracy`);
        expect(res.body.page).toBe(page);
        ids.push(...res.body.results.map((r: any) => r.id));
      }
      expect(ids).toHaveLength(TOTAL);
      expect(new Set(ids).size).toBe(TOTAL);
    });

    it("returns a short last page and an empty page past the end", async () => {
      const lastPage = Math.ceil(TOTAL / 50);
      const last = await list(`page=${lastPage}`);
      expect(last.body.results).toHaveLength(TOTAL - (lastPage - 1) * 50);
      const beyond = await list(`page=${lastPage + 5}`);
      expect(beyond.status).toBe(200);
      expect(beyond.body).toMatchObject({ results: [], total: TOTAL });
    });

    it("paginates within a filter and sort", async () => {
      const page1 = await list("model=BulkModel&sort=-confidenceAccuracy&pageSize=10");
      const page2 = await list("model=BulkModel&sort=-confidenceAccuracy&pageSize=10&page=2");
      const values = [...page1.body.results, ...page2.body.results].map((r: any) => r.confidenceAccuracy);
      expect(values).toEqual([...values].sort((a, b) => b - a));
      expect(page1.body.total).toBe(120);
    });

    it("accepts the maximum page size", async () => {
      const res = await list("pageSize=100");
      expect(res.body.results).toHaveLength(100);
    });
  });
});

describe("GET /api/results/filter-options", () => {
  it("lists distinct models and categories from the database, sorted", async () => {
    const res = await api("/results/filter-options", { token });
    expect(res.status).toBe(200);
    expect(Object.keys(res.body).sort()).toEqual(["categories", "models", "subCategories"]);
    expect(res.body.models).toEqual(["BulkModel", "CardioNet", "ChestScan", "NeuroSlice"]);
    expect(res.body.categories).toEqual(["Bulk", "Cardiology", "Neurology", "Pulmonology"]);
  });

  it("narrows sub categories to the selected categories", async () => {
    const one = await api("/results/filter-options?category=Neurology", { token });
    expect(one.body.subCategories).toEqual(["Stroke", "Tumor"]);
    const two = await api("/results/filter-options?category=Neurology&category=Pulmonology", { token });
    expect(two.body.subCategories).toEqual(["Nodule", "Pneumonia", "Stroke", "Tumor"]);
    expect(two.body.models).toHaveLength(4);
  });

  it("returns no sub categories for an unknown category", async () => {
    const res = await api("/results/filter-options?category=Nope", { token });
    expect(res.status).toBe(200);
    expect(res.body.subCategories).toEqual([]);
  });

  it("rejects unsupported parameters", async () => {
    const res = await api("/results/filter-options?model=CardioNet", { token });
    expect(res.status).toBe(400);
  });

  it("requires authentication", async () => {
    expect((await api("/results/filter-options")).status).toBe(401);
  });
});

describe("GET /api/results/:id", () => {
  it("returns one result and its stored payload", async () => {
    const [first] = (await list("model=NeuroSlice&sort=version")).body.results;
    const one = await api(`/results/${first.id}`, { token });
    expect(one.status).toBe(200);
    expect(one.body).toEqual(first);
    const payload = await api(`/results/${first.id}/payload`, { token });
    expect(payload.status).toBe(200);
    expect(payload.body).toEqual({ modelName: "NeuroSlice", version: "0.5.0", notes: "seed NeuroSlice 0.5.0" });
  });

  it("returns 404 for an unknown id", async () => {
    const res = await api("/results/11111111-1111-4111-8111-111111111111", { token });
    expect(res.status).toBe(404);
    expect(res.body.status).toBe(404);
  });

  it.each(["not-a-uuid", "1", "11111111-1111-4111-8111"])("returns 400 for the malformed id %s", async (id) => {
    const res = await api(`/results/${id}`, { token });
    expect(res.status).toBe(400);
  });
});
