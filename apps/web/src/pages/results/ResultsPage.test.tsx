import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import type { UserEvent } from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it, vi } from "vitest";
import type { UserRole } from "../../shared/api/types";
import { largeResults, makeResult } from "../../test/fixtures";
import { renderApp, tableRows } from "../../test/render";
import { API, db, lastResultsQuery, server } from "../../test/server";

const COL = { model: 0, version: 1, category: 3, subCategory: 4, confidence: 5, sensitivity: 6, size: 11, vectorized: 13 };

async function settled() {
  await waitFor(() => {
    expect(screen.getByText(/^Page \d+ of \d+$/)).toBeInTheDocument();
    expect(document.querySelector(".results-table-wrap.is-loading")).toBeNull();
  });
}

async function openResults(path = "/results", as: UserRole = "member") {
  const rendered = renderApp(path, { as });
  await screen.findByRole("heading", { name: "Model Results" });
  await settled();
  return rendered;
}

async function openFilters(user: UserEvent) {
  await user.click(screen.getByRole("button", { name: /^Filters/ }));
  expect(document.getElementById("results-filters")).toBeVisible();
}

function filterBar() {
  return within(document.getElementById("results-filters")!);
}

function multi(label: string) {
  const root = screen.getByText(label, { selector: ".multi-select-label" }).closest(".multi-select") as HTMLElement;
  return within(root);
}

async function toggleOptions(user: UserEvent, label: string, ...options: string[]) {
  const m = multi(label);
  if (m.getByRole("button").getAttribute("aria-expanded") !== "true") await user.click(m.getByRole("button"));
  for (const option of options) {
    await user.click(await m.findByRole("checkbox", { name: option }));
    await settled();
  }
  await user.keyboard("{Escape}");
}

async function applyMetric(user: UserEvent, label: string, operator: string, value: string) {
  await user.selectOptions(screen.getByLabelText(`${label} operator`), operator);
  const input = screen.getByLabelText(`${label} value`);
  await user.clear(input);
  await user.type(input, `${value}{Enter}`);
  await settled();
}

function column(index: number): string[] {
  return tableRows().map((row) => row[index]);
}

function search(): string {
  return window.location.search;
}

describe("ResultsPage", () => {
  describe("data states", () => {
    it("shows a loading state until results arrive", async () => {
      server.use(
        http.get(`${API}/results`, async () => {
          await new Promise((resolve) => setTimeout(resolve, 150));
          return HttpResponse.json({ results: [], total: 0, page: 1, pageSize: 50 });
        }),
      );
      renderApp("/results", { as: "member" });
      await screen.findByRole("heading", { name: "Model Results" });
      expect(screen.getByText("Loading…")).toBeInTheDocument();
      expect(document.querySelector(".results-table-wrap.is-loading")).not.toBeNull();
      expect(screen.getByRole("button", { name: "Next" })).toBeDisabled();
      expect(screen.getByRole("button", { name: "Previous" })).toBeDisabled();
      expect(screen.queryByText("No results match the current filters.")).toBeNull();
      await settled();
    });

    it("renders every row from the API with formatted values", async () => {
      await openResults();
      expect(screen.getByRole("heading", { name: "Results (8)" })).toBeInTheDocument();
      expect(screen.getByText("Page 1 of 1")).toBeInTheDocument();
      const rows = tableRows();
      expect(rows).toHaveLength(8);
      expect(column(COL.model)).toEqual([
        "LegacyModel",
        "CardioNet",
        "ChestScan",
        "ChestScan",
        "NeuroSlice",
        "NeuroSlice",
        "CardioNet",
        "CardioNet",
      ]);
      const [legacy, cardio3, pneumonia, , tumor, , , cardio1] = rows;
      expect(legacy[COL.category]).toBe("—");
      expect(legacy[COL.subCategory]).toBe("—");
      expect(legacy[COL.size]).toBe("512 B");
      expect(legacy[COL.sensitivity]).toBe("0.00");
      expect(legacy[COL.vectorized]).toBe("pending");
      expect(cardio3[COL.confidence]).toBe("100.00");
      expect(cardio3[COL.size]).toBe("3.00 MB");
      expect(cardio3[COL.vectorized]).toBe("yes");
      expect(pneumonia.slice(COL.confidence, COL.confidence + 6)).toEqual(["—", "—", "—", "—", "—", "—"]);
      expect(tumor[COL.vectorized]).toBe("pending");
      expect(cardio1[COL.confidence]).toBe("97.50");
      expect(cardio1[9]).toBe("91.25");
      expect(cardio1[COL.size]).toBe("2.0 KB");
      expect(cardio1[12]).not.toBe("—");
    });

    it("shows the empty state when nothing exists", async () => {
      db.results = [];
      await openResults();
      expect(screen.getByRole("heading", { name: "Results (0)" })).toBeInTheDocument();
      expect(screen.getByText("No results match the current filters.")).toBeInTheDocument();
      expect(screen.getByText("Page 1 of 1")).toBeInTheDocument();
      expect(screen.getByRole("button", { name: "Next" })).toBeDisabled();
    });

    it("shows the server error message when results fail to load", async () => {
      server.use(http.get(`${API}/results`, () => HttpResponse.json({ message: "Unexpected server error" }, { status: 500 })));
      await openResults();
      expect(screen.getByText("Unexpected server error")).toHaveClass("error");
      expect(screen.getByRole("heading", { name: "Results (0)" })).toBeInTheDocument();
    });

    it("shows an error when the API is unreachable", async () => {
      server.use(http.get(`${API}/results`, () => HttpResponse.error()));
      await openResults();
      expect(document.querySelector("p.error")?.textContent).toMatch(/fetch/i);
    });

    it("shows an error when filter options fail to load", async () => {
      server.use(
        http.get(`${API}/results/filter-options`, () => HttpResponse.json({ message: "Options unavailable" }, { status: 503 })),
      );
      await openResults();
      expect(await screen.findByText("Options unavailable")).toHaveClass("error");
    });

    it("clears a previous error once a later request succeeds", async () => {
      server.use(
        http.get(`${API}/results`, () => HttpResponse.json({ message: "Temporary failure" }, { status: 500 }), { once: true }),
      );
      const { user } = await openResults();
      expect(screen.getByText("Temporary failure")).toBeInTheDocument();
      await user.click(screen.getByRole("button", { name: "Model" }));
      await settled();
      expect(screen.queryByText("Temporary failure")).toBeNull();
      expect(tableRows()).toHaveLength(8);
    });

    it("renders a large page of results", async () => {
      db.results = largeResults(250);
      await openResults("/results?pageSize=100");
      expect(tableRows()).toHaveLength(100);
      expect(screen.getByRole("heading", { name: "Results (250)" })).toBeInTheDocument();
      expect(screen.getByText("Page 1 of 3")).toBeInTheDocument();
    });
  });

  describe("filters", () => {
    it("loads the Model, Category and Sub Category choices from the API", async () => {
      db.results = [...db.results, makeResult({ modelName: "BrandNewModel", category: "Oncology", subCategory: "Breast" })];
      const { user } = await openResults();
      await openFilters(user);
      await user.click(multi("Model").getByRole("button"));
      expect(multi("Model").getAllByRole("checkbox").map((c) => c.parentElement?.textContent)).toEqual([
        "BrandNewModel",
        "CardioNet",
        "ChestScan",
        "LegacyModel",
        "NeuroSlice",
      ]);
      await user.keyboard("{Escape}");
      await user.click(multi("Category").getByRole("button"));
      expect(multi("Category").getAllByRole("checkbox").map((c) => c.parentElement?.textContent)).toEqual([
        "Cardiology",
        "Neurology",
        "Oncology",
        "Pulmonology",
      ]);
    });

    it("has no Version filter and never sends a version param", async () => {
      const { user } = await openResults("/results?version=1.0.0&modelName=CardioNet");
      await openFilters(user);
      expect(filterBar().queryByText(/version/i)).toBeNull();
      expect([...document.querySelectorAll(".multi-select-label")].map((e) => e.textContent)).toEqual([
        "Model",
        "Category",
        "Sub Category",
      ]);
      expect(lastResultsQuery().has("version")).toBe(false);
      expect(lastResultsQuery().has("modelName")).toBe(false);
      expect(tableRows()).toHaveLength(8);
    });

    it("filters by model", async () => {
      const { user } = await openResults();
      await openFilters(user);
      await toggleOptions(user, "Model", "NeuroSlice");
      expect(lastResultsQuery().getAll("model")).toEqual(["NeuroSlice"]);
      expect(new Set(column(COL.model))).toEqual(new Set(["NeuroSlice"]));
      expect(tableRows()).toHaveLength(2);
      await toggleOptions(user, "Model", "ChestScan");
      expect(lastResultsQuery().getAll("model")).toEqual(["NeuroSlice", "ChestScan"]);
      expect(tableRows()).toHaveLength(4);
    });

    it("narrows Sub Category choices to the selected categories", async () => {
      const { user } = await openResults();
      await openFilters(user);
      await toggleOptions(user, "Category", "Neurology");
      await waitFor(() =>
        expect(db.filterOptionRequests.at(-1)?.searchParams.getAll("category")).toEqual(["Neurology"]),
      );
      await user.click(multi("Sub Category").getByRole("button"));
      await waitFor(() =>
        expect(multi("Sub Category").getAllByRole("checkbox").map((c) => c.parentElement?.textContent)).toEqual([
          "Stroke",
          "Tumor",
        ]),
      );
      expect(new Set(column(COL.category))).toEqual(new Set(["Neurology"]));
    });

    it("drops a selected sub category that no longer belongs to the chosen categories", async () => {
      const { user } = await openResults("/results?category=Cardiology&subCategory=Aortic%20Valve");
      expect(tableRows()).toHaveLength(2);
      await openFilters(user);
      await toggleOptions(user, "Category", "Cardiology", "Neurology");
      await waitFor(() => expect(new URLSearchParams(search()).getAll("subCategory")).toEqual([]));
      await settled();
      expect(new URLSearchParams(search()).getAll("category")).toEqual(["Neurology"]);
      expect(tableRows()).toHaveLength(2);
      expect(new Set(column(COL.category))).toEqual(new Set(["Neurology"]));
    });

    it("filters by sub category", async () => {
      const { user } = await openResults();
      await openFilters(user);
      await toggleOptions(user, "Sub Category", "Aortic Valve");
      expect(lastResultsQuery().getAll("subCategory")).toEqual(["Aortic Valve"]);
      expect(column(COL.subCategory)).toEqual(["Aortic Valve", "Aortic Valve"]);
    });

    it("filters by vectorized status", async () => {
      const { user } = await openResults();
      await openFilters(user);
      await user.selectOptions(filterBar().getByRole("combobox", { name: "Vectorized" }), "false");
      await settled();
      expect(lastResultsQuery().get("vectorized")).toBe("false");
      expect(new Set(column(COL.vectorized))).toEqual(new Set(["pending"]));
      await user.selectOptions(filterBar().getByRole("combobox", { name: "Vectorized" }), "all");
      await settled();
      expect(lastResultsQuery().has("vectorized")).toBe(false);
    });

    it.each([
      ["=", "confidenceAccuracyEq", 1],
      [">", "confidenceAccuracyGt", 3],
      [">=", "confidenceAccuracyGte", 4],
      ["<", "confidenceAccuracyLt", 3],
      ["<=", "confidenceAccuracyLte", 4],
    ])("filters confidence with the %s operator", async (operator, param, expected) => {
      const { user } = await openResults();
      await openFilters(user);
      await applyMetric(user, "Confidence/Accuracy", operator, "95");
      expect(lastResultsQuery().get(param)).toBe("95");
      expect(tableRows()).toHaveLength(expected);
      expect(column(COL.confidence)).not.toContain("—");
    });

    it("filters with a numeric range", async () => {
      const { user } = await openResults();
      await openFilters(user);
      await user.type(screen.getByLabelText("Confidence/Accuracy value"), "90");
      const group = screen.getByRole("group", { name: "Confidence/Accuracy" });
      await user.click(within(group).getByRole("button", { name: "Add range" }));
      await user.type(screen.getByLabelText("Confidence/Accuracy second value"), "98{Enter}");
      await settled();
      expect(lastResultsQuery().get("confidenceAccuracyGte")).toBe("90");
      expect(lastResultsQuery().get("confidenceAccuracyLte")).toBe("98");
      expect(column(COL.confidence).sort()).toEqual(["94.99", "95.00", "97.50"]);
    });

    it("refuses an inverted range without sending a request", async () => {
      const { user } = await openResults();
      await openFilters(user);
      await user.type(screen.getByLabelText("Sensitivity value"), "98");
      const group = screen.getByRole("group", { name: "Sensitivity" });
      await user.click(within(group).getByRole("button", { name: "Add range" }));
      await settled();
      const before = db.resultRequests.length;
      await user.type(screen.getByLabelText("Sensitivity second value"), "90{Enter}");
      expect(within(group).getByText("Minimum is greater than maximum")).toBeInTheDocument();
      expect(db.resultRequests.length).toBe(before);
      expect(new URLSearchParams(search()).has("sensitivityLte")).toBe(false);
    });

    it("refuses an out-of-range value without sending a request", async () => {
      const { user } = await openResults();
      await openFilters(user);
      const before = db.resultRequests.length;
      await user.type(screen.getByLabelText("Precision value"), "100.5{Enter}");
      expect(screen.getByText("Value must be between 0 and 100")).toBeInTheDocument();
      expect(db.resultRequests.length).toBe(before);
    });

    it("combines model, category and several metric filters", async () => {
      const { user } = await openResults();
      await openFilters(user);
      await toggleOptions(user, "Model", "CardioNet");
      await toggleOptions(user, "Category", "Cardiology");
      await applyMetric(user, "Confidence/Accuracy", ">=", "95");
      await applyMetric(user, "Sensitivity", ">", "95");
      const q = lastResultsQuery();
      expect(q.getAll("model")).toEqual(["CardioNet"]);
      expect(q.getAll("category")).toEqual(["Cardiology"]);
      expect(q.get("confidenceAccuracyGte")).toBe("95");
      expect(q.get("sensitivityGt")).toBe("95");
      expect(tableRows()).toHaveLength(1);
      expect(tableRows()[0][COL.version]).toBe("3.0.0");
      expect(screen.getByRole("button", { name: /^Filters/ })).toHaveTextContent("4");
      expect(filterBar().getByText("4 filters applied")).toBeInTheDocument();
    });

    it("shows the empty state when a combination matches nothing", async () => {
      const { user } = await openResults();
      await openFilters(user);
      await toggleOptions(user, "Model", "LegacyModel");
      await toggleOptions(user, "Category", "Cardiology");
      expect(screen.getByText("No results match the current filters.")).toBeInTheDocument();
      expect(screen.getByRole("heading", { name: "Results (0)" })).toBeInTheDocument();
    });

    it("restores filters from the URL", async () => {
      const { user } = await openResults("/results?model=ChestScan&confidenceAccuracyGte=95&sort=-sensitivity");
      expect(lastResultsQuery().get("model")).toBe("ChestScan");
      expect(lastResultsQuery().get("confidenceAccuracyGte")).toBe("95");
      expect(lastResultsQuery().get("sort")).toBe("-sensitivity");
      expect(tableRows()).toHaveLength(1);
      expect(screen.getByRole("button", { name: /^Filters/ })).toHaveTextContent("2");
      await openFilters(user);
      expect(screen.getByLabelText("Confidence/Accuracy value")).toHaveValue(95);
      expect(multi("Model").getByRole("button")).toHaveTextContent("ChestScan");
    });

    it("toggles the filter panel", async () => {
      const { user } = await openResults();
      const toggle = screen.getByRole("button", { name: /^Filters/ });
      expect(toggle).toHaveAttribute("aria-expanded", "false");
      expect(document.getElementById("results-filters")).not.toBeVisible();
      await user.click(toggle);
      expect(toggle).toHaveAttribute("aria-expanded", "true");
      await user.click(toggle);
      expect(document.getElementById("results-filters")).not.toBeVisible();
    });
  });

  describe("clear filters", () => {
    it("is disabled when nothing is applied", async () => {
      const { user } = await openResults();
      await openFilters(user);
      expect(filterBar().getByRole("button", { name: "Clear filters" })).toBeDisabled();
      expect(filterBar().getByText("No filters applied")).toBeInTheDocument();
    });

    it("resets every filter, discards drafts and shows all results again", async () => {
      const { user } = await openResults();
      await openFilters(user);
      await toggleOptions(user, "Model", "CardioNet");
      await toggleOptions(user, "Category", "Cardiology");
      await user.selectOptions(filterBar().getByRole("combobox", { name: "Vectorized" }), "true");
      await settled();
      await applyMetric(user, "Confidence/Accuracy", ">=", "95");
      await user.type(screen.getByLabelText("Recall value"), "500{Enter}");
      expect(screen.getByText("Value must be between 0 and 100")).toBeInTheDocument();
      expect(tableRows()).toHaveLength(2);
      expect(filterBar().getByText("4 filters applied")).toBeInTheDocument();

      await user.click(filterBar().getByRole("button", { name: "Clear filters" }));
      await settled();

      expect(search()).toBe("");
      const q = lastResultsQuery();
      expect([...q.keys()]).toEqual([]);
      expect(tableRows()).toHaveLength(8);
      expect(filterBar().getByText("No filters applied")).toBeInTheDocument();
      expect(filterBar().getByRole("button", { name: "Clear filters" })).toBeDisabled();
      expect(screen.getByRole("button", { name: /^Filters/ })).toHaveTextContent(/^Filters$/);
      expect(screen.getByLabelText("Confidence/Accuracy value")).toHaveValue(null);
      expect(screen.getByLabelText("Recall value")).toHaveValue(null);
      expect(screen.queryByText("Value must be between 0 and 100")).toBeNull();
      expect(multi("Model").getByRole("button")).toHaveTextContent("All");
      expect(multi("Category").getByRole("button")).toHaveTextContent("All");
      expect(multi("Sub Category").getByRole("button")).toHaveTextContent("All");
      expect(filterBar().getByRole("combobox", { name: "Vectorized" })).toHaveValue("all");
    });

    it("keeps the sort and page size but returns to page 1", async () => {
      db.results = largeResults(120);
      const { user } = await openResults("/results?model=CardioNet&pageSize=25&page=2&sort=confidenceAccuracy");
      await openFilters(user);
      await user.click(filterBar().getByRole("button", { name: "Clear filters" }));
      await settled();
      const params = new URLSearchParams(search());
      expect(params.get("sort")).toBe("confidenceAccuracy");
      expect(params.get("pageSize")).toBe("25");
      expect(params.has("page")).toBe(false);
      expect(params.has("model")).toBe(false);
      expect(screen.getByText("Page 1 of 5")).toBeInTheDocument();
    });
  });

  describe("sorting", () => {
    function th(name: string) {
      return screen.getByRole("button", { name }).closest("th")!;
    }

    it("defaults to newest first", async () => {
      await openResults();
      expect(th("Inserted")).toHaveAttribute("aria-sort", "descending");
      expect(lastResultsQuery().has("sort")).toBe(false);
    });

    it("cycles a metric column ascending, descending, then back to the default", async () => {
      const { user } = await openResults();
      await user.click(screen.getByRole("button", { name: "Sensitivity" }));
      await settled();
      expect(lastResultsQuery().get("sort")).toBe("sensitivity");
      expect(th("Sensitivity")).toHaveAttribute("aria-sort", "ascending");
      expect(th("Inserted")).toHaveAttribute("aria-sort", "none");
      expect(column(COL.sensitivity)).toEqual(["0.00", "9.50", "85.00", "92.00", "96.00", "98.00", "100.00", "—"]);

      await user.click(screen.getByRole("button", { name: "Sensitivity" }));
      await settled();
      expect(lastResultsQuery().get("sort")).toBe("-sensitivity");
      expect(th("Sensitivity")).toHaveAttribute("aria-sort", "descending");
      expect(column(COL.sensitivity)).toEqual(["100.00", "98.00", "96.00", "92.00", "85.00", "9.50", "0.00", "—"]);

      await user.click(screen.getByRole("button", { name: "Sensitivity" }));
      await settled();
      expect(lastResultsQuery().has("sort")).toBe(false);
      expect(th("Sensitivity")).toHaveAttribute("aria-sort", "none");
      expect(th("Inserted")).toHaveAttribute("aria-sort", "descending");
    });

    it("toggles a text column between ascending and descending", async () => {
      const { user } = await openResults();
      await user.click(screen.getByRole("button", { name: "Model" }));
      await settled();
      expect(lastResultsQuery().get("sort")).toBe("modelName");
      expect(column(COL.model)[0]).toBe("CardioNet");
      await user.click(screen.getByRole("button", { name: "Model" }));
      await settled();
      expect(lastResultsQuery().get("sort")).toBe("-modelName");
      expect(column(COL.model)[0]).toBe("NeuroSlice");
    });

    it("flips the default date sort to oldest first", async () => {
      const { user } = await openResults();
      await user.click(screen.getByRole("button", { name: "Inserted" }));
      await settled();
      expect(lastResultsQuery().get("sort")).toBe("insertedAt");
      expect(th("Inserted")).toHaveAttribute("aria-sort", "ascending");
      expect(tableRows()[0][COL.version]).toBe("1.0.0");
    });

    it("keeps filters when sorting and returns to page 1", async () => {
      db.results = largeResults(200);
      const { user } = await openResults("/results?model=CardioNet&page=2&pageSize=25");
      await user.click(screen.getByRole("button", { name: "Confidence/Accuracy" }));
      await settled();
      const q = lastResultsQuery();
      expect(q.get("model")).toBe("CardioNet");
      expect(q.get("sort")).toBe("confidenceAccuracy");
      expect(q.has("page")).toBe(false);
      const values = column(COL.confidence).map(Number);
      expect(values).toEqual([...values].sort((a, b) => a - b));
    });
  });

  describe("pagination", () => {
    it("pages through a large result set", async () => {
      db.results = largeResults(250);
      const { user } = await openResults();
      expect(screen.getByText("Page 1 of 5")).toBeInTheDocument();
      expect(screen.getByRole("button", { name: "Previous" })).toBeDisabled();
      const firstPage = column(2);
      expect(firstPage).toHaveLength(50);

      await user.click(screen.getByRole("button", { name: "Next" }));
      await settled();
      expect(lastResultsQuery().get("page")).toBe("2");
      expect(screen.getByText("Page 2 of 5")).toBeInTheDocument();
      const secondPage = column(2);
      expect(secondPage).toHaveLength(50);
      expect(secondPage.filter((id) => firstPage.includes(id))).toEqual([]);

      await user.click(screen.getByRole("button", { name: "Previous" }));
      await settled();
      expect(lastResultsQuery().has("page")).toBe(false);
      expect(column(2)).toEqual(firstPage);
    });

    it("disables Next on the last page", async () => {
      db.results = largeResults(120);
      await openResults("/results?page=3");
      expect(screen.getByText("Page 3 of 3")).toBeInTheDocument();
      expect(tableRows()).toHaveLength(20);
      expect(screen.getByRole("button", { name: "Next" })).toBeDisabled();
      expect(screen.getByRole("button", { name: "Previous" })).toBeEnabled();
    });

    it("moves back to the last page when the URL points past the end", async () => {
      db.results = largeResults(120);
      await openResults("/results?page=99");
      await waitFor(() => expect(new URLSearchParams(search()).get("page")).toBe("3"));
      await settled();
      expect(screen.getByText("Page 3 of 3")).toBeInTheDocument();
      expect(tableRows()).toHaveLength(20);
    });

    it("changes the page size and returns to page 1", async () => {
      db.results = largeResults(250);
      const { user } = await openResults("/results?page=3");
      await user.selectOptions(screen.getByRole("combobox", { name: "Page size" }), "100");
      await settled();
      expect(lastResultsQuery().get("pageSize")).toBe("100");
      expect(lastResultsQuery().has("page")).toBe(false);
      expect(screen.getByText("Page 1 of 3")).toBeInTheDocument();
      expect(tableRows()).toHaveLength(100);
      await user.selectOptions(screen.getByRole("combobox", { name: "Page size" }), "25");
      await settled();
      expect(screen.getByText("Page 1 of 10")).toBeInTheDocument();
    });

    it("offers an unusual page size from the URL", async () => {
      db.results = largeResults(30);
      await openResults("/results?pageSize=7");
      const sizes = within(screen.getByRole("combobox", { name: "Page size" }))
        .getAllByRole("option")
        .map((o) => o.textContent);
      expect(sizes).toEqual(["7", "25", "50", "100"]);
      expect(screen.getByText("Page 1 of 5")).toBeInTheDocument();
    });

    it("returns to page 1 when a filter changes", async () => {
      db.results = largeResults(250);
      const { user } = await openResults("/results?page=4");
      await openFilters(user);
      await toggleOptions(user, "Model", "ChestScan");
      expect(lastResultsQuery().has("page")).toBe(false);
      expect(screen.getByText(/^Page 1 of/)).toBeInTheDocument();
      await applyMetric(user, "Confidence/Accuracy", ">=", "50");
      expect(lastResultsQuery().has("page")).toBe(false);
    });

    it("keeps filters and sort while paging", async () => {
      db.results = largeResults(250);
      const { user } = await openResults("/results?model=CardioNet&sort=-confidenceAccuracy&pageSize=25");
      await user.click(screen.getByRole("button", { name: "Next" }));
      await settled();
      const q = lastResultsQuery();
      expect(q.get("model")).toBe("CardioNet");
      expect(q.get("sort")).toBe("-confidenceAccuracy");
      expect(q.get("pageSize")).toBe("25");
      expect(q.get("page")).toBe("2");
      expect(new Set(column(COL.model))).toEqual(new Set(["CardioNet"]));
    });
  });

  describe("submit result modal", () => {
    async function openModal() {
      const rendered = await openResults("/results", "superadmin");
      await rendered.user.click(screen.getByRole("button", { name: "Submit result" }));
      return { ...rendered, dialog: within(screen.getByRole("dialog", { name: "Submit model result" })) };
    }

    async function fill(user: UserEvent, dialog: ReturnType<typeof within>, values: Record<string, string>) {
      for (const [label, value] of Object.entries(values)) {
        const input = dialog.getByLabelText(label);
        await user.clear(input);
        await user.type(input, value);
      }
    }

    const VALID = {
      "Model name": "  NewNet  ",
      Version: "4.2.0",
      Category: "Cardiology",
      "Sub category": "Aortic Valve",
      "Confidence/Accuracy": "96.5",
      Sensitivity: "0",
      Specificity: "100",
      "F1 Score": "91",
      Recall: "92",
      Precision: "93",
    };

    it.each(["member", "admin"] as const)("is not available to %s", async (role) => {
      await openResults("/results", role);
      expect(screen.queryByRole("button", { name: "Submit result" })).toBeNull();
      expect(screen.queryByRole("button", { name: "Reindex vectors" })).toBeNull();
    });

    it("opens with empty required fields and an example payload", async () => {
      const { dialog } = await openModal();
      for (const label of ["Model name", "Version", "Category", "Sub category"]) {
        expect(dialog.getByLabelText(label)).toBeRequired();
        expect(dialog.getByLabelText(label)).toHaveValue("");
      }
      for (const label of ["Confidence/Accuracy", "Sensitivity", "Specificity", "F1 Score", "Recall", "Precision"]) {
        expect(dialog.getByLabelText(label)).toBeRequired();
        expect(dialog.getByLabelText(label)).toHaveValue(null);
        expect(dialog.getByLabelText(label)).toHaveAttribute("min", "0");
        expect(dialog.getByLabelText(label)).toHaveAttribute("max", "100");
      }
      expect((document.querySelector("textarea.json-textarea") as HTMLTextAreaElement).value).toContain('"example"');
    });

    it("closes on Cancel without submitting", async () => {
      const { user, dialog } = await openModal();
      await fill(user, dialog, { "Model name": "Discard me" });
      await user.click(dialog.getByRole("button", { name: "Cancel" }));
      expect(screen.queryByRole("dialog")).toBeNull();
      expect(db.createdResults).toEqual([]);
    });

    it("closes when the backdrop is clicked but not when the dialog is clicked", async () => {
      const { user } = await openModal();
      const dialogEl = screen.getByRole("dialog");
      await user.click(dialogEl);
      expect(screen.getByRole("dialog")).toBeInTheDocument();
      await user.click(dialogEl.parentElement!);
      expect(screen.queryByRole("dialog")).toBeNull();
    });

    it("submits a valid result, closes, and refreshes the table", async () => {
      const { user, dialog } = await openModal();
      await fill(user, dialog, VALID);
      const textarea = document.querySelector("textarea.json-textarea") as HTMLTextAreaElement;
      await user.clear(textarea);
      await user.click(textarea);
      await user.paste('{"scores":[0.9,0.8],"note":"ok"}');
      const before = db.resultRequests.length;
      await user.click(dialog.getByRole("button", { name: "Submit" }));

      await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
      expect(db.createdResults).toEqual([
        {
          modelName: "NewNet",
          version: "4.2.0",
          category: "Cardiology",
          subCategory: "Aortic Valve",
          confidenceAccuracy: 96.5,
          sensitivity: 0,
          specificity: 100,
          f1Score: 91,
          recall: 92,
          precision: 93,
          payload: { scores: [0.9, 0.8], note: "ok" },
        },
      ]);
      expect(screen.getByText("Result ingested. The worker will vectorize it shortly.")).toBeInTheDocument();
      await settled();
      expect(db.resultRequests.length).toBeGreaterThan(before);
      expect(screen.getByRole("heading", { name: "Results (9)" })).toBeInTheDocument();
    });

    it("rejects a payload that is not valid JSON", async () => {
      const { user, dialog } = await openModal();
      await fill(user, dialog, VALID);
      const textarea = document.querySelector("textarea.json-textarea") as HTMLTextAreaElement;
      await user.clear(textarea);
      await user.click(textarea);
      await user.paste("{not json");
      await user.click(dialog.getByRole("button", { name: "Submit" }));
      expect(await screen.findByText("Payload must be valid JSON")).toBeInTheDocument();
      expect(screen.getByRole("dialog")).toBeInTheDocument();
      expect(db.createdResults).toEqual([]);
    });

    it.each([
      ["Confidence/Accuracy", "150"],
      ["Recall", "-1"],
    ])("rejects %s = %s", async (label, value) => {
      const { user, dialog } = await openModal();
      await fill(user, dialog, { ...VALID, [label]: value });
      fireEvent.submit(screen.getByRole("dialog").querySelector("form")!);
      expect(await screen.findByText(`${label} must be between 0 and 100`)).toBeInTheDocument();
      expect(db.createdResults).toEqual([]);
    });

    it("keeps the modal open and shows the API error", async () => {
      server.use(
        http.post(`${API}/results`, () =>
          HttpResponse.json({ message: "modelName must be at most 128 characters" }, { status: 400 }),
        ),
      );
      const { user, dialog } = await openModal();
      await fill(user, dialog, VALID);
      await user.click(dialog.getByRole("button", { name: "Submit" }));
      expect(await screen.findByText("modelName must be at most 128 characters")).toBeInTheDocument();
      expect(screen.getByRole("dialog")).toBeInTheDocument();
      expect(dialog.getByRole("button", { name: "Submit" })).toBeEnabled();
    });

    it("disables the buttons and ignores backdrop clicks while submitting", async () => {
      let release: () => void = () => {};
      server.use(
        http.post(`${API}/results`, async () => {
          await new Promise<void>((resolve) => (release = resolve));
          return HttpResponse.json(makeResult(), { status: 201 });
        }),
      );
      const { user, dialog } = await openModal();
      await fill(user, dialog, VALID);
      await user.click(dialog.getByRole("button", { name: "Submit" }));
      expect(await dialog.findByRole("button", { name: "Submitting…" })).toBeDisabled();
      expect(dialog.getByRole("button", { name: "Cancel" })).toBeDisabled();
      await user.click(screen.getByRole("dialog").parentElement!);
      expect(screen.getByRole("dialog")).toBeInTheDocument();
      release();
      await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    });

    it("fits a short viewport by scrolling inside the modal body", async () => {
      await openModal();
      const dialogEl = screen.getByRole("dialog");
      expect(dialogEl).toHaveClass("modal-scrollable");
      expect(dialogEl.querySelector(".modal-body")).not.toBeNull();
      expect(dialogEl.querySelector(".modal-actions")?.parentElement?.classList.contains("modal-body")).toBe(false);
    });
  });

  describe("payload viewer and reindex", () => {
    it("shows the pretty-printed JSON payload", async () => {
      const { user } = await openResults();
      await user.click(screen.getAllByRole("button", { name: "View JSON" })[0]);
      const dialog = await screen.findByRole("dialog", { name: /LegacyModel · 0\.1\.0/ });
      await waitFor(() => expect(within(dialog).getByText(/"model": "LegacyModel"/)).toBeInTheDocument());
      await user.click(within(dialog).getByRole("button", { name: "Close" }));
      expect(screen.queryByRole("dialog")).toBeNull();
    });

    it("shows raw text when the payload is not JSON", async () => {
      server.use(http.get(`${API}/results/:id/payload`, () => new HttpResponse("plain text payload")));
      const { user } = await openResults();
      await user.click(screen.getAllByRole("button", { name: "View JSON" })[1]);
      expect(await screen.findByText("plain text payload")).toBeInTheDocument();
    });

    it("shows a failure message when the payload cannot be loaded", async () => {
      server.use(http.get(`${API}/results/:id/payload`, () => HttpResponse.json({ message: "Result file is missing" }, { status: 500 })));
      const { user } = await openResults();
      await user.click(screen.getAllByRole("button", { name: "View JSON" })[0]);
      expect(await screen.findByText("Failed to load: Result file is missing")).toBeInTheDocument();
    });

    it("does nothing when reindex is not confirmed", async () => {
      vi.spyOn(window, "confirm").mockReturnValue(false);
      const { user } = await openResults("/results", "superadmin");
      await user.click(screen.getByRole("button", { name: "Reindex vectors" }));
      expect(window.confirm).toHaveBeenCalled();
      expect(db.results.every((r) => r.vectorized === false)).toBe(false);
    });

    it("reindexes after confirmation and reports the count", async () => {
      vi.spyOn(window, "confirm").mockReturnValue(true);
      const { user } = await openResults("/results", "superadmin");
      await user.click(screen.getByRole("button", { name: "Reindex vectors" }));
      expect(await screen.findByText("Vector store cleared (8 rows)")).toBeInTheDocument();
      await settled();
      expect(new Set(column(COL.vectorized))).toEqual(new Set(["pending"]));
    });

    it("shows the error when reindex fails", async () => {
      vi.spyOn(window, "confirm").mockReturnValue(true);
      server.use(http.post(`${API}/results/reindex`, () => HttpResponse.json({ message: "Reindex failed: busy" }, { status: 500 })));
      const { user } = await openResults("/results", "superadmin");
      await user.click(screen.getByRole("button", { name: "Reindex vectors" }));
      expect(await screen.findByText("Reindex failed: busy")).toBeInTheDocument();
      expect(screen.getByRole("button", { name: "Reindex vectors" })).toBeEnabled();
    });
  });
});
