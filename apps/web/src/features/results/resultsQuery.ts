export const METRIC_FIELDS = [
  { key: "confidenceAccuracy", label: "Confidence/Accuracy" },
  { key: "sensitivity", label: "Sensitivity" },
  { key: "specificity", label: "Specificity" },
  { key: "f1Score", label: "F1 Score" },
  { key: "recall", label: "Recall" },
  { key: "precision", label: "Precision" },
] as const;

export type MetricKey = (typeof METRIC_FIELDS)[number]["key"];

export type LowerOp = "gt" | "gte";
export type UpperOp = "lt" | "lte";

export interface MetricFilter {
  exact?: string;
  lowerOp?: LowerOp;
  lower?: string;
  upperOp?: UpperOp;
  upper?: string;
}

export interface ResultsQuery {
  models: string[];
  categories: string[];
  subCategories: string[];
  vectorized: "all" | "true" | "false";
  metrics: Partial<Record<MetricKey, MetricFilter>>;
  page: number;
  pageSize: number;
  sort: string;
}

export const DEFAULT_PAGE_SIZE = 50;
export const DEFAULT_SORT = "-insertedAt";

const METRIC_KEYS = new Set<string>(METRIC_FIELDS.map((field) => field.key));

export function emptyResultsQuery(): ResultsQuery {
  return {
    models: [],
    categories: [],
    subCategories: [],
    vectorized: "all",
    metrics: {},
    page: 1,
    pageSize: DEFAULT_PAGE_SIZE,
    sort: DEFAULT_SORT,
  };
}

export function parseResultsQuery(params: URLSearchParams): ResultsQuery {
  const query = emptyResultsQuery();
  query.models = params.getAll("model").map((value) => value.trim()).filter(Boolean);
  query.categories = params.getAll("category").map((value) => value.trim()).filter(Boolean);
  query.subCategories = params.getAll("subCategory").map((value) => value.trim()).filter(Boolean);
  const vectorized = params.get("vectorized");
  if (vectorized === "true" || vectorized === "false") {
    query.vectorized = vectorized;
  }
  const page = Number(params.get("page"));
  if (Number.isInteger(page) && page >= 1) {
    query.page = page;
  }
  const pageSize = Number(params.get("pageSize"));
  if (Number.isInteger(pageSize) && pageSize >= 1 && pageSize <= 100) {
    query.pageSize = pageSize;
  }
  const sort = params.get("sort");
  if (sort) {
    query.sort = sort;
  }

  for (const key of METRIC_KEYS) {
    const metricKey = key as MetricKey;
    const exact = params.get(`${key}Eq`);
    const gt = params.get(`${key}Gt`);
    const gte = params.get(`${key}Gte`);
    const lt = params.get(`${key}Lt`);
    const lte = params.get(`${key}Lte`);
    const filter: MetricFilter = {};
    if (exact) {
      filter.exact = exact;
    } else {
      if (gt) {
        filter.lowerOp = "gt";
        filter.lower = gt;
      } else if (gte) {
        filter.lowerOp = "gte";
        filter.lower = gte;
      }
      if (lt) {
        filter.upperOp = "lt";
        filter.upper = lt;
      } else if (lte) {
        filter.upperOp = "lte";
        filter.upper = lte;
      }
    }
    if (filter.exact || filter.lower || filter.upper) {
      query.metrics[metricKey] = filter;
    }
  }
  return query;
}

export function serializeResultsQuery(query: ResultsQuery): URLSearchParams {
  const params = new URLSearchParams();
  for (const model of query.models) {
    if (model) params.append("model", model);
  }
  for (const category of query.categories) {
    if (category) params.append("category", category);
  }
  for (const subCategory of query.subCategories) {
    if (subCategory) params.append("subCategory", subCategory);
  }
  if (query.vectorized === "true" || query.vectorized === "false") {
    params.set("vectorized", query.vectorized);
  }
  for (const { key } of METRIC_FIELDS) {
    const metric = query.metrics[key];
    if (!metric) continue;
    if (metric.exact) {
      params.set(`${key}Eq`, metric.exact);
      continue;
    }
    if (metric.lower && metric.lowerOp) {
      params.set(`${key}${metric.lowerOp === "gt" ? "Gt" : "Gte"}`, metric.lower);
    }
    if (metric.upper && metric.upperOp) {
      params.set(`${key}${metric.upperOp === "lt" ? "Lt" : "Lte"}`, metric.upper);
    }
  }
  if (query.page > 1) params.set("page", String(query.page));
  if (query.pageSize !== DEFAULT_PAGE_SIZE) params.set("pageSize", String(query.pageSize));
  if (query.sort && query.sort !== DEFAULT_SORT) params.set("sort", query.sort);
  return params;
}

export function metricFilterError(filter: MetricFilter | undefined): string | null {
  if (!filter) return null;
  if (filter.exact) {
    return numberError(filter.exact);
  }
  if (filter.lower) {
    const message = numberError(filter.lower);
    if (message) return message;
  }
  if (filter.upper) {
    const message = numberError(filter.upper);
    if (message) return message;
  }
  if (filter.lower && filter.upper) {
    const low = Number(filter.lower);
    const high = Number(filter.upper);
    if (low > high) return "Minimum is greater than maximum";
    if (low === high && filter.lowerOp === "gt" && filter.upperOp === "lt") {
      return "Range excludes every value";
    }
  }
  return null;
}

export function toggleSort(current: string, field: string, preferDesc: boolean): string {
  if (current === `-${field}`) return field;
  if (current === field) return `-${field}`;
  return preferDesc ? `-${field}` : field;
}

/** Ascending, then descending, then back to the default sort. */
export function cycleSort(current: string, field: string): string {
  if (current === field) return `-${field}`;
  if (current === `-${field}`) return DEFAULT_SORT;
  return field;
}

function numberError(raw: string): string | null {
  const trimmed = raw.trim();
  if (!trimmed) return "Enter a number";
  const value = Number(trimmed);
  if (!Number.isFinite(value)) return "Enter a number";
  if (value < 0 || value > 100) return "Value must be between 0 and 100";
  return null;
}
