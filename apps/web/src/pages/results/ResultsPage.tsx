import { useEffect, useMemo, useRef, useState, type FormEvent } from "react";
import { useSearchParams } from "react-router-dom";
import { MultiSelectFilter } from "../../features/results/MultiSelectFilter";
import { NumericFilter } from "../../features/results/NumericFilter";
import {
  METRIC_FIELDS,
  cycleSort,
  parseResultsQuery,
  serializeResultsQuery,
  toggleSort,
  type MetricFilter,
  type MetricKey,
  type ResultsQuery,
} from "../../features/results/resultsQuery";
import { useAuth } from "../../features/auth/AuthContext";
import {
  createResult,
  fetchFilterOptions,
  fetchResultPayload,
  listResults,
  reindexResults,
} from "../../shared/api/client";
import type { FilterOptions, ResultPage, ResultSummary } from "../../shared/api/types";

function formatDateTime(value: string | null | undefined): string {
  if (!value) return "—";
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return "—";
  return d.toLocaleString(undefined, {
    year: "numeric",
    month: "short",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  });
}

function formatBytes(n: number): string {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / (1024 * 1024)).toFixed(2)} MB`;
}

function formatMetric(value: number | null): string {
  if (value == null || Number.isNaN(value)) return "—";
  return new Intl.NumberFormat(undefined, {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(value);
}

function parsePercent(label: string, raw: string): number {
  const value = Number(raw);
  if (!Number.isFinite(value) || value < 0 || value > 100) {
    throw new Error(`${label} must be between 0 and 100`);
  }
  return value;
}

function isAbort(err: unknown): boolean {
  return err instanceof Error && err.name === "AbortError";
}

function withSelected(options: string[], selected: string[]): string[] {
  const seen = new Set(options);
  return [...options, ...selected.filter((item) => !seen.has(item))];
}

function emptyCreateForm() {
  return {
    modelName: "",
    version: "",
    category: "",
    subCategory: "",
    confidenceAccuracy: "",
    sensitivity: "",
    specificity: "",
    f1Score: "",
    recall: "",
    precision: "",
    payload: '{\n  "example": "paste your model output JSON here"\n}',
  };
}

export function ResultsPage() {
  const { token, user } = useAuth();
  const [params, setParams] = useSearchParams();
  const paramsKey = params.toString();
  const query = useMemo(() => parseResultsQuery(params), [params, paramsKey]);
  const queryRef = useRef(query);
  queryRef.current = query;
  const [pageData, setPageData] = useState<ResultPage | null>(null);
  const [options, setOptions] = useState<FilterOptions>({ categories: [], subCategories: [] });
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [info, setInfo] = useState<string | null>(null);
  const [reloadKey, setReloadKey] = useState(0);
  const [modelDraft, setModelDraft] = useState(query.modelName);
  const [versionDraft, setVersionDraft] = useState(query.version);
  const [createOpen, setCreateOpen] = useState(false);
  const [form, setForm] = useState(emptyCreateForm);
  const [creating, setCreating] = useState(false);
  const [reindexing, setReindexing] = useState(false);
  const [payloadFor, setPayloadFor] = useState<ResultSummary | null>(null);
  const [payloadText, setPayloadText] = useState<string>("");
  const [payloadLoading, setPayloadLoading] = useState(false);
  const [filtersOpen, setFiltersOpen] = useState(false);

  const canReindex = user?.role === "superadmin";
  const canSubmit = user?.role === "superadmin";
  const categoryKey = query.categories.join("\u0000");
  const results = pageData?.results ?? [];
  const total = pageData?.total ?? 0;
  const pageCount = Math.max(1, Math.ceil(total / query.pageSize));
  const activeFilterCount =
    (query.modelName ? 1 : 0) +
    (query.version ? 1 : 0) +
    (query.vectorized !== "all" ? 1 : 0) +
    (query.categories.length > 0 ? 1 : 0) +
    (query.subCategories.length > 0 ? 1 : 0) +
    Object.keys(query.metrics).length;

  function commitQuery(next: ResultsQuery, resetPage: boolean) {
    setParams(serializeResultsQuery(resetPage ? { ...next, page: 1 } : next));
  }

  useEffect(() => {
    setModelDraft(query.modelName);
  }, [query.modelName]);

  useEffect(() => {
    setVersionDraft(query.version);
  }, [query.version]);

  useEffect(() => {
    if (modelDraft === query.modelName) return;
    const timer = window.setTimeout(() => {
      setParams(serializeResultsQuery({ ...query, modelName: modelDraft, page: 1 }));
    }, 300);
    return () => window.clearTimeout(timer);
  }, [modelDraft, query, setParams]);

  useEffect(() => {
    if (versionDraft === query.version) return;
    const timer = window.setTimeout(() => {
      setParams(serializeResultsQuery({ ...query, version: versionDraft, page: 1 }));
    }, 300);
    return () => window.clearTimeout(timer);
  }, [versionDraft, query, setParams]);

  useEffect(() => {
    if (!token) return;
    const controller = new AbortController();
    setLoading(true);
    setError(null);
    listResults(token, params, controller.signal)
      .then((page) => setPageData(page))
      .catch((err: unknown) => {
        if (isAbort(err)) return;
        setError(err instanceof Error ? err.message : "Failed to load results");
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [token, params, paramsKey, reloadKey]);

  useEffect(() => {
    if (!token) return;
    const controller = new AbortController();
    const categories = query.categories;
    fetchFilterOptions(token, categories, controller.signal)
      .then((opts) => {
        setOptions(opts);
        const latest = queryRef.current;
        const allowed = new Set(opts.subCategories);
        const kept = latest.subCategories.filter((item) => allowed.has(item));
        if (kept.length !== latest.subCategories.length) {
          setParams(serializeResultsQuery({ ...latest, subCategories: kept, page: 1 }));
        }
      })
      .catch((err: unknown) => {
        if (isAbort(err)) return;
        setError(err instanceof Error ? err.message : "Failed to load filter options");
      });
    return () => controller.abort();
  }, [token, categoryKey, setParams]);

  useEffect(() => {
    if (!pageData || query.page <= pageCount) return;
    setParams(serializeResultsQuery({ ...query, page: pageCount }));
  }, [pageData, pageCount, query, setParams]);

  function setMetric(key: MetricKey, next: MetricFilter | undefined) {
    const metrics = { ...query.metrics };
    if (next) metrics[key] = next;
    else delete metrics[key];
    commitQuery({ ...query, metrics }, true);
  }

  async function onCreate(e: FormEvent) {
    e.preventDefault();
    if (!token) return;
    setCreating(true);
    setError(null);
    setInfo(null);
    let parsedPayload: unknown;
    try {
      parsedPayload = JSON.parse(form.payload);
    } catch {
      setError("Payload must be valid JSON");
      setCreating(false);
      return;
    }
    try {
      await createResult(token, {
        modelName: form.modelName.trim(),
        version: form.version.trim(),
        category: form.category.trim(),
        subCategory: form.subCategory.trim(),
        confidenceAccuracy: parsePercent("Confidence/Accuracy", form.confidenceAccuracy),
        sensitivity: parsePercent("Sensitivity", form.sensitivity),
        specificity: parsePercent("Specificity", form.specificity),
        f1Score: parsePercent("F1 Score", form.f1Score),
        recall: parsePercent("Recall", form.recall),
        precision: parsePercent("Precision", form.precision),
        payload: parsedPayload,
      });
      setCreateOpen(false);
      setForm(emptyCreateForm());
      setInfo("Result ingested. The worker will vectorize it shortly.");
      setReloadKey((value) => value + 1);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Create failed");
    } finally {
      setCreating(false);
    }
  }

  async function onReindex() {
    if (!token) return;
    if (!confirm("Reindex all results? Vector store will be cleared and rebuilt by the worker.")) {
      return;
    }
    setReindexing(true);
    setError(null);
    setInfo(null);
    try {
      const res = await reindexResults(token);
      setInfo(`${res.message} (${res.reset} rows)`);
      setReloadKey((value) => value + 1);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Reindex failed");
    } finally {
      setReindexing(false);
    }
  }

  async function viewPayload(r: ResultSummary) {
    if (!token) return;
    setPayloadFor(r);
    setPayloadText("");
    setPayloadLoading(true);
    try {
      const raw = await fetchResultPayload(token, r.id);
      try {
        setPayloadText(JSON.stringify(JSON.parse(raw), null, 2));
      } catch {
        setPayloadText(raw);
      }
    } catch (err) {
      setPayloadText(`Failed to load: ${err instanceof Error ? err.message : "error"}`);
    } finally {
      setPayloadLoading(false);
    }
  }

  const pageSizes = [25, 50, 100].includes(query.pageSize) ? [25, 50, 100] : [query.pageSize, 25, 50, 100];

  return (
    <section className="panel panel-wide">
      <div className="panel-header">
        <h1>Model Results</h1>
        <div className="actions">
          {canReindex ? (
            <button
              type="button"
              className="btn ghost"
              disabled={reindexing}
              onClick={() => void onReindex()}
            >
              {reindexing ? "Resetting…" : "Reindex vectors"}
            </button>
          ) : null}
          {canSubmit ? (
            <button type="button" className="btn primary" onClick={() => setCreateOpen(true)}>
              Submit result
            </button>
          ) : null}
        </div>
      </div>

      {error ? <p className="error">{error}</p> : null}
      {info ? <p className="muted">{info}</p> : null}

      <div className="pager">
        <h2>
          Results ({total})
        </h2>
        <button
          type="button"
          className={filtersOpen ? "btn ghost filters-toggle open" : "btn ghost filters-toggle"}
          aria-expanded={filtersOpen}
          aria-controls="results-filters"
          onClick={() => setFiltersOpen((open) => !open)}
        >
          Filters
          {activeFilterCount > 0 ? <span className="filters-count">{activeFilterCount}</span> : null}
        </button>
        <span className="muted">
          {loading ? "Loading…" : `Page ${query.page} of ${pageCount}`}
        </span>
        <button
          type="button"
          className="btn ghost"
          disabled={query.page <= 1 || loading}
          onClick={() => commitQuery({ ...query, page: query.page - 1 }, false)}
        >
          Previous
        </button>
        <button
          type="button"
          className="btn ghost"
          disabled={query.page >= pageCount || loading}
          onClick={() => commitQuery({ ...query, page: query.page + 1 }, false)}
        >
          Next
        </button>
        <label className="page-size">
          Page size
          <select
            value={query.pageSize}
            onChange={(e) => commitQuery({ ...query, pageSize: Number(e.target.value) }, true)}
          >
            {pageSizes.map((size) => (
              <option key={size} value={size}>
                {size}
              </option>
            ))}
          </select>
        </label>
      </div>

      <div id="results-filters" className="filter-bar" hidden={!filtersOpen}>
        <div className="grid">
          <label>
            Model
            <input
              value={modelDraft}
              onChange={(e) => setModelDraft(e.target.value)}
              placeholder="e.g. M1"
            />
          </label>
          <label>
            Version
            <input
              value={versionDraft}
              onChange={(e) => setVersionDraft(e.target.value)}
              placeholder="e.g. 1.0.0"
            />
          </label>
          <label>
            Vectorized
            <select
              value={query.vectorized}
              onChange={(e) =>
                commitQuery({ ...query, vectorized: e.target.value as ResultsQuery["vectorized"] }, true)
              }
            >
              <option value="all">All</option>
              <option value="true">Yes</option>
              <option value="false">No</option>
            </select>
          </label>
          <MultiSelectFilter
            label="Category"
            options={withSelected(options.categories, query.categories)}
            selected={query.categories}
            onChange={(categories) => commitQuery({ ...query, categories }, true)}
          />
          <MultiSelectFilter
            label="Sub Category"
            options={withSelected(options.subCategories, query.subCategories)}
            selected={query.subCategories}
            onChange={(subCategories) => commitQuery({ ...query, subCategories }, true)}
          />
        </div>
        <div className="metric-filters">
          {METRIC_FIELDS.map((field) => (
            <NumericFilter
              key={field.key}
              label={field.label}
              value={query.metrics[field.key]}
              onChange={(next) => setMetric(field.key, next)}
            />
          ))}
        </div>
      </div>

      <div className={loading ? "table-wrap results-table-wrap is-loading" : "table-wrap results-table-wrap"}>
        <table className="results-table">
          <thead>
            <tr>
              <SortTh label="Model" field="modelName" sort={query.sort} onSort={(field) => commitQuery({ ...query, sort: toggleSort(query.sort, field, false) }, true)} />
              <SortTh label="Version" field="version" sort={query.sort} onSort={(field) => commitQuery({ ...query, sort: toggleSort(query.sort, field, false) }, true)} />
              <SortTh label="Iteration" field="iterationId" sort={query.sort} onSort={(field) => commitQuery({ ...query, sort: toggleSort(query.sort, field, false) }, true)} />
              <SortTh label="Category" field="category" sort={query.sort} onSort={(field) => commitQuery({ ...query, sort: toggleSort(query.sort, field, false) }, true)} />
              <SortTh label="Sub Category" field="subCategory" sort={query.sort} onSort={(field) => commitQuery({ ...query, sort: toggleSort(query.sort, field, false) }, true)} />
              {METRIC_FIELDS.map((field) => (
                <SortTh
                  key={field.key}
                  label={field.label}
                  field={field.key}
                  sort={query.sort}
                  onSort={(name) => commitQuery({ ...query, sort: cycleSort(query.sort, name) }, true)}
                />
              ))}
              <th>Size</th>
              <SortTh label="Inserted" field="insertedAt" sort={query.sort} onSort={(field) => commitQuery({ ...query, sort: toggleSort(query.sort, field, true) }, true)} />
              <th>Vectorized</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {results.map((r) => (
              <tr key={r.id}>
                <td className="cell-text cell-primary" title={r.modelName}>{r.modelName}</td>
                <td className="cell-text" title={r.version}>{r.version}</td>
                <td className="cell-mono">{r.iterationId}</td>
                <td className="cell-text" title={r.category ?? undefined}>{r.category ?? "—"}</td>
                <td className="cell-text" title={r.subCategory ?? undefined}>{r.subCategory ?? "—"}</td>
                <td className="cell-num">{formatMetric(r.confidenceAccuracy)}</td>
                <td className="cell-num">{formatMetric(r.sensitivity)}</td>
                <td className="cell-num">{formatMetric(r.specificity)}</td>
                <td className="cell-num">{formatMetric(r.f1Score)}</td>
                <td className="cell-num">{formatMetric(r.recall)}</td>
                <td className="cell-num">{formatMetric(r.precision)}</td>
                <td className="cell-num">{formatBytes(r.sizeBytes)}</td>
                <td className="cell-date">{formatDateTime(r.insertedAt)}</td>
                <td className="cell-status">
                  {r.vectorized ? (
                    <span className="badge success">yes</span>
                  ) : (
                    <span className="badge pending">pending</span>
                  )}
                </td>
                <td className="cell-action">
                  <button type="button" className="btn ghost" onClick={() => void viewPayload(r)}>
                    View JSON
                  </button>
                </td>
              </tr>
            ))}
            {!loading && results.length === 0 ? (
              <tr>
                <td className="cell-empty" colSpan={15}>
                  <span className="muted">No results match the current filters.</span>
                </td>
              </tr>
            ) : null}
          </tbody>
        </table>
      </div>

      {canSubmit && createOpen ? (
        <div
          className="modal-backdrop"
          role="presentation"
          onClick={() => (creating ? undefined : setCreateOpen(false))}
        >
          <div
            className="modal modal-wide modal-scrollable"
            role="dialog"
            aria-modal="true"
            aria-labelledby="create-result-title"
            onClick={(e) => e.stopPropagation()}
          >
            <h2 id="create-result-title">Submit model result</h2>
            <form className="modal-form" onSubmit={onCreate}>
              <div className="modal-body">
                <fieldset className="form-section">
                  <legend>Metadata</legend>
                  <div className="grid">
                    <label>
                      Model name
                      <input
                        value={form.modelName}
                        onChange={(e) => setForm((f) => ({ ...f, modelName: e.target.value }))}
                        required
                      />
                    </label>
                    <label>
                      Version
                      <input
                        value={form.version}
                        onChange={(e) => setForm((f) => ({ ...f, version: e.target.value }))}
                        required
                      />
                    </label>
                    <label>
                      Category
                      <input
                        value={form.category}
                        onChange={(e) => setForm((f) => ({ ...f, category: e.target.value }))}
                        required
                      />
                    </label>
                    <label>
                      Sub category
                      <input
                        value={form.subCategory}
                        onChange={(e) => setForm((f) => ({ ...f, subCategory: e.target.value }))}
                        required
                      />
                    </label>
                    <MetricInput
                      label="Confidence/Accuracy"
                      value={form.confidenceAccuracy}
                      onChange={(value) => setForm((f) => ({ ...f, confidenceAccuracy: value }))}
                    />
                    <MetricInput
                      label="Sensitivity"
                      value={form.sensitivity}
                      onChange={(value) => setForm((f) => ({ ...f, sensitivity: value }))}
                    />
                    <MetricInput
                      label="Specificity"
                      value={form.specificity}
                      onChange={(value) => setForm((f) => ({ ...f, specificity: value }))}
                    />
                    <MetricInput
                      label="F1 Score"
                      value={form.f1Score}
                      onChange={(value) => setForm((f) => ({ ...f, f1Score: value }))}
                    />
                    <MetricInput
                      label="Recall"
                      value={form.recall}
                      onChange={(value) => setForm((f) => ({ ...f, recall: value }))}
                    />
                    <MetricInput
                      label="Precision"
                      value={form.precision}
                      onChange={(value) => setForm((f) => ({ ...f, precision: value }))}
                    />
                  </div>
                </fieldset>
                <fieldset className="form-section">
                  <legend>Payload (JSON)</legend>
                  <textarea
                    className="json-textarea"
                    value={form.payload}
                    onChange={(e) => setForm((f) => ({ ...f, payload: e.target.value }))}
                    rows={12}
                    required
                  />
                  <p className="muted">
                    The full JSON is stored verbatim on disk under <code>data/results/</code>. Category,
                    subcategory, and metrics are stored on the result row as percentage points from 0 to 100.
                  </p>
                </fieldset>
              </div>
              <div className="modal-actions">
                <button
                  type="button"
                  className="btn ghost"
                  disabled={creating}
                  onClick={() => setCreateOpen(false)}
                >
                  Cancel
                </button>
                <button className="btn primary" type="submit" disabled={creating}>
                  {creating ? "Submitting…" : "Submit"}
                </button>
              </div>
            </form>
          </div>
        </div>
      ) : null}

      {payloadFor ? (
        <div className="modal-backdrop" role="presentation" onClick={() => setPayloadFor(null)}>
          <div
            className="modal modal-wide"
            role="dialog"
            aria-modal="true"
            aria-labelledby="payload-title"
            onClick={(e) => e.stopPropagation()}
          >
            <h2 id="payload-title">
              {payloadFor.modelName} · {payloadFor.version} · {payloadFor.iterationId}
            </h2>
            {payloadLoading ? <p>Loading…</p> : <pre className="json-pre">{payloadText}</pre>}
            <div className="modal-actions">
              <button type="button" className="btn ghost" onClick={() => setPayloadFor(null)}>
                Close
              </button>
            </div>
          </div>
        </div>
      ) : null}
    </section>
  );
}

function SortTh({
  label,
  field,
  sort,
  onSort,
}: {
  label: string;
  field: string;
  sort: string;
  onSort: (field: string) => void;
}) {
  const descending = sort === `-${field}`;
  const ascending = sort === field;
  const active = ascending || descending;
  const mark = descending ? "↓" : ascending ? "↑" : "";
  return (
    <th aria-sort={descending ? "descending" : ascending ? "ascending" : "none"}>
      <button type="button" className={active ? "sort-button active" : "sort-button"} onClick={() => onSort(field)}>
        <span>{label}</span>
        <span className="sort-mark" aria-hidden="true">
          {mark}
        </span>
      </button>
    </th>
  );
}

function MetricInput({
  label,
  value,
  onChange,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
}) {
  return (
    <label>
      {label}
      <input
        type="number"
        min={0}
        max={100}
        step="any"
        required
        value={value}
        onChange={(e) => onChange(e.target.value)}
      />
    </label>
  );
}
