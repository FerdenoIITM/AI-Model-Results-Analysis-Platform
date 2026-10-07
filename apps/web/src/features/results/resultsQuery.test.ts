import { describe, expect, it } from "vitest";
import {
  DEFAULT_SORT,
  cycleSort,
  emptyResultsQuery,
  metricFilterError,
  parseResultsQuery,
  serializeResultsQuery,
  toggleSort,
  type ResultsQuery,
} from "./resultsQuery";

describe("resultsQuery", () => {
  it("omits default page, page size, and sort", () => {
    expect(serializeResultsQuery(emptyResultsQuery()).toString()).toBe("");
    expect(parseResultsQuery(new URLSearchParams())).toEqual(emptyResultsQuery());
  });

  it("round-trips combined filters", () => {
    const query: ResultsQuery = {
      ...emptyResultsQuery(),
      categories: ["Cardiology", "Neurology"],
      subCategories: ["Aortic Valve"],
      modelName: "M1",
      version: "1.0.0",
      vectorized: "true",
      metrics: {
        confidenceAccuracy: { lowerOp: "gte", lower: "95" },
        sensitivity: { lowerOp: "gt", lower: "90", upperOp: "lt", upper: "98" },
        f1Score: { lowerOp: "gte", lower: "90", upperOp: "lte", upper: "95" },
        precision: { exact: "97.3" },
      },
      page: 2,
      pageSize: 25,
      sort: "-confidenceAccuracy",
    };

    expect(parseResultsQuery(serializeResultsQuery(query))).toEqual(query);
  });

  it("rejects an inverted range and values outside 0 to 100", () => {
    expect(metricFilterError({ lowerOp: "gte", lower: "98", upperOp: "lte", upper: "90" })).toBe(
      "Minimum is greater than maximum",
    );
    expect(metricFilterError({ lowerOp: "gt", lower: "90", upperOp: "lt", upper: "90" })).toBe(
      "Range excludes every value",
    );
    expect(metricFilterError({ lowerOp: "gte", lower: "101" })).toBe("Value must be between 0 and 100");
    expect(metricFilterError({ lowerOp: "gte", lower: "95", upperOp: "lte", upper: "98" })).toBeNull();
  });

  it("toggles sort direction", () => {
    expect(toggleSort("-insertedAt", "insertedAt", false)).toBe("insertedAt");
    expect(toggleSort("insertedAt", "insertedAt", false)).toBe("-insertedAt");
    expect(toggleSort("-insertedAt", "confidenceAccuracy", true)).toBe("-confidenceAccuracy");
  });

  it("cycles metric sort ascending, descending, then default", () => {
    const first = cycleSort(DEFAULT_SORT, "f1Score");
    const second = cycleSort(first, "f1Score");
    const third = cycleSort(second, "f1Score");
    expect([first, second, third]).toEqual(["f1Score", "-f1Score", DEFAULT_SORT]);
    expect(cycleSort("-recall", "precision")).toBe("precision");
    expect(serializeResultsQuery({ ...emptyResultsQuery(), sort: third }).has("sort")).toBe(false);
  });
});
