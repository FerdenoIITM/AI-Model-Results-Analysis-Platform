import { useEffect, useMemo, useState, type FormEvent } from "react";
import { useAuth } from "../../features/auth/AuthContext";
import {
  createResult,
  fetchResultPayload,
  listResults,
  reindexResults,
} from "../../shared/api/client";
import type { ResultSummary } from "../../shared/api/types";

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

function emptyCreateForm() {
  return {
    modelName: "",
    version: "",
    iterationId: "",
    payload: '{\n  "example": "paste your model output JSON here"\n}',
  };
}

export function ResultsPage() {
  const { token, user } = useAuth();
  const [results, setResults] = useState<ResultSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [info, setInfo] = useState<string | null>(null);
  const [modelFilter, setModelFilter] = useState("");
  const [versionFilter, setVersionFilter] = useState("");
  const [vectorFilter, setVectorFilter] = useState<"all" | "yes" | "no">("all");
  const [createOpen, setCreateOpen] = useState(false);
  const [form, setForm] = useState(emptyCreateForm);
  const [creating, setCreating] = useState(false);
  const [reindexing, setReindexing] = useState(false);
  const [payloadFor, setPayloadFor] = useState<ResultSummary | null>(null);
  const [payloadText, setPayloadText] = useState<string>("");
  const [payloadLoading, setPayloadLoading] = useState(false);

  const canReindex = user?.role === "superadmin";

  async function refresh() {
    if (!token) return;
    setLoading(true);
    setError(null);
    try {
      setResults(await listResults(token, 200));
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to load results");
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void refresh();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token]);

  const filtered = useMemo(() => {
    const m = modelFilter.trim().toLowerCase();
    const v = versionFilter.trim().toLowerCase();
    return results.filter((r) => {
      if (m && !r.modelName.toLowerCase().includes(m)) return false;
      if (v && !r.version.toLowerCase().includes(v)) return false;
      if (vectorFilter === "yes" && !r.vectorized) return false;
      if (vectorFilter === "no" && r.vectorized) return false;
      return true;
    });
  }, [results, modelFilter, versionFilter, vectorFilter]);

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
        iterationId: form.iterationId.trim(),
        payload: parsedPayload,
      });
      setCreateOpen(false);
      setForm(emptyCreateForm());
      setInfo("Result ingested. The worker will vectorize it shortly.");
      await refresh();
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
      await refresh();
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

  return (
    <section className="panel">
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
          <button type="button" className="btn primary" onClick={() => setCreateOpen(true)}>
            Submit result
          </button>
        </div>
      </div>

      {error ? <p className="error">{error}</p> : null}
      {info ? <p className="muted">{info}</p> : null}

      <div className="grid">
        <label>
          Model
          <input
            value={modelFilter}
            onChange={(e) => setModelFilter(e.target.value)}
            placeholder="e.g. M1"
          />
        </label>
        <label>
          Version
          <input
            value={versionFilter}
            onChange={(e) => setVersionFilter(e.target.value)}
            placeholder="e.g. 1.0.0"
          />
        </label>
        <label>
          Vectorized
          <select value={vectorFilter} onChange={(e) => setVectorFilter(e.target.value as typeof vectorFilter)}>
            <option value="all">All</option>
            <option value="yes">Yes</option>
            <option value="no">No</option>
          </select>
        </label>
      </div>

      <h2>Results ({filtered.length})</h2>
      {loading ? (
        <p>Loading…</p>
      ) : (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Model</th>
                <th>Version</th>
                <th>Iteration</th>
                <th>Size</th>
                <th>Inserted</th>
                <th>Vectorized</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {filtered.map((r) => (
                <tr key={r.id}>
                  <td>{r.modelName}</td>
                  <td>{r.version}</td>
                  <td>{r.iterationId}</td>
                  <td className="nowrap">{formatBytes(r.sizeBytes)}</td>
                  <td className="nowrap">{formatDateTime(r.insertedAt)}</td>
                  <td>
                    {r.vectorized ? (
                      <span className="badge success">yes</span>
                    ) : (
                      <span className="badge pending">pending</span>
                    )}
                  </td>
                  <td className="actions">
                    <button type="button" className="btn ghost" onClick={() => void viewPayload(r)}>
                      View JSON
                    </button>
                  </td>
                </tr>
              ))}
              {filtered.length === 0 ? (
                <tr>
                  <td colSpan={7}>
                    <span className="muted">No results match the current filters.</span>
                  </td>
                </tr>
              ) : null}
            </tbody>
          </table>
        </div>
      )}

      {createOpen ? (
        <div
          className="modal-backdrop"
          role="presentation"
          onClick={() => (creating ? undefined : setCreateOpen(false))}
        >
          <div
            className="modal modal-wide"
            role="dialog"
            aria-modal="true"
            aria-labelledby="create-result-title"
            onClick={(e) => e.stopPropagation()}
          >
            <h2 id="create-result-title">Submit model result</h2>
            <form className="modal-form" onSubmit={onCreate}>
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
                    Iteration id
                    <input
                      value={form.iterationId}
                      onChange={(e) => setForm((f) => ({ ...f, iterationId: e.target.value }))}
                      required
                    />
                  </label>
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
                  The full JSON is stored verbatim on disk under <code>data/results/</code>. The
                  worker will flatten and embed it for chat retrieval.
                </p>
              </fieldset>
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
        <div
          className="modal-backdrop"
          role="presentation"
          onClick={() => setPayloadFor(null)}
        >
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
            {payloadLoading ? (
              <p>Loading…</p>
            ) : (
              <pre className="json-pre">{payloadText}</pre>
            )}
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
