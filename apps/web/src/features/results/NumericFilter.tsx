import { useEffect, useRef, useState } from "react";
import { metricFilterError, type MetricFilter } from "./resultsQuery";

type PrimaryOp = "=" | ">" | ">=" | "<" | "<=";
type SecondaryOp = ">" | ">=" | "<" | "<=";

interface Draft {
  primaryOp: PrimaryOp;
  primaryValue: string;
  secondaryOp: SecondaryOp;
  secondaryValue: string;
  ranged: boolean;
}

const PRIMARY_OPS: PrimaryOp[] = ["=", ">", ">=", "<", "<="];

interface NumericFilterProps {
  label: string;
  value: MetricFilter | undefined;
  onChange: (next: MetricFilter | undefined) => void;
}

export function NumericFilter({ label, value, onChange }: NumericFilterProps) {
  const [draft, setDraft] = useState<Draft>(() => fromMetric(value));
  const [error, setError] = useState<string | null>(null);
  const incoming = JSON.stringify(value ?? null);
  const draftRef = useRef(draft);
  draftRef.current = draft;

  useEffect(() => {
    const next = fromMetric(value);
    draftRef.current = next;
    setDraft(next);
    setError(null);
  }, [incoming]);

  function commit(nextDraft: Draft) {
    const next = toMetric(nextDraft);
    const message = metricFilterError(next);
    if (message) {
      setError(message);
      return;
    }
    setError(null);
    if (JSON.stringify(next ?? null) === incoming) return;
    onChange(next);
  }

  function update(nextDraft: Draft, commitNow: boolean) {
    draftRef.current = nextDraft;
    setDraft(nextDraft);
    if (commitNow) commit(nextDraft);
  }

  function setPrimaryValue(primaryValue: string) {
    const next = { ...draftRef.current, primaryValue };
    draftRef.current = next;
    setDraft(next);
  }

  function setSecondaryValue(secondaryValue: string) {
    const next = { ...draftRef.current, secondaryValue };
    draftRef.current = next;
    setDraft(next);
  }

  const secondaryChoices = oppositeOps(draft.primaryOp);

  return (
    <fieldset className="numeric-filter">
      <legend>{label}</legend>
      <div className="numeric-filter-row">
        <select
          aria-label={`${label} operator`}
          value={draft.primaryOp}
          onChange={(event) => {
            const primaryOp = event.target.value as PrimaryOp;
            const ranged = primaryOp !== "=" && draft.ranged;
            const secondaryOp = ranged && !isOpposite(primaryOp, draft.secondaryOp)
              ? oppositeOps(primaryOp)[0]
              : draft.secondaryOp;
            update(
              { ...draft, primaryOp, ranged, secondaryOp },
              Boolean(draft.primaryValue.trim() || (ranged && draft.secondaryValue.trim())),
            );
          }}
        >
          {PRIMARY_OPS.map((op) => (
            <option key={op} value={op}>
              {op}
            </option>
          ))}
        </select>
        <input
          type="number"
          min={0}
          max={100}
          step="any"
          placeholder="0–100"
          aria-label={`${label} value`}
          value={draft.primaryValue}
          onChange={(event) => setPrimaryValue(event.target.value)}
          onBlur={() => commit(draftRef.current)}
          onKeyDown={(event) => {
            if (event.key === "Enter") {
              event.preventDefault();
              commit(draftRef.current);
            }
          }}
        />
      </div>
      {draft.ranged && draft.primaryOp !== "=" ? (
        <div className="numeric-filter-row">
          <select
            aria-label={`${label} second operator`}
            value={secondaryChoices.includes(draft.secondaryOp) ? draft.secondaryOp : secondaryChoices[0]}
            onChange={(event) => {
              const secondaryOp = event.target.value as SecondaryOp;
              update(
                { ...draft, secondaryOp },
                Boolean(draft.primaryValue.trim() || draft.secondaryValue.trim()),
              );
            }}
          >
            {secondaryChoices.map((op) => (
              <option key={op} value={op}>
                {op}
              </option>
            ))}
          </select>
          <input
            type="number"
            min={0}
            max={100}
            step="any"
            placeholder="0–100"
            aria-label={`${label} second value`}
            value={draft.secondaryValue}
            onChange={(event) => setSecondaryValue(event.target.value)}
            onBlur={() => commit(draftRef.current)}
            onKeyDown={(event) => {
              if (event.key === "Enter") {
                event.preventDefault();
                commit(draftRef.current);
              }
            }}
          />
        </div>
      ) : null}
      <div className="numeric-filter-actions">
        {draft.primaryOp !== "=" && !draft.ranged ? (
          <button
            type="button"
            className="btn ghost"
            onClick={() =>
              setDraft((current) => ({
                ...current,
                ranged: true,
                secondaryOp: oppositeOps(current.primaryOp)[0],
              }))
            }
          >
            Add range
          </button>
        ) : null}
        {draft.ranged ? (
          <button
            type="button"
            className="btn ghost"
            onClick={() => {
              const next = { ...draft, ranged: false, secondaryValue: "" };
              update(next, true);
            }}
          >
            Remove range
          </button>
        ) : null}
        {value || draft.primaryValue || draft.secondaryValue ? (
          <button
            type="button"
            className="btn ghost"
            onClick={() => {
              setDraft(fromMetric(undefined));
              setError(null);
              onChange(undefined);
            }}
          >
            Clear
          </button>
        ) : null}
      </div>
      {error ? <p className="error">{error}</p> : null}
    </fieldset>
  );
}

function fromMetric(value: MetricFilter | undefined): Draft {
  if (value?.exact) {
    return {
      primaryOp: "=",
      primaryValue: value.exact,
      secondaryOp: "<=",
      secondaryValue: "",
      ranged: false,
    };
  }
  if (value?.lower && value.upper) {
    return {
      primaryOp: value.lowerOp === "gt" ? ">" : ">=",
      primaryValue: value.lower,
      secondaryOp: value.upperOp === "lt" ? "<" : "<=",
      secondaryValue: value.upper,
      ranged: true,
    };
  }
  if (value?.lower) {
    return {
      primaryOp: value.lowerOp === "gt" ? ">" : ">=",
      primaryValue: value.lower,
      secondaryOp: "<=",
      secondaryValue: "",
      ranged: false,
    };
  }
  if (value?.upper) {
    return {
      primaryOp: value.upperOp === "lt" ? "<" : "<=",
      primaryValue: value.upper,
      secondaryOp: ">=",
      secondaryValue: "",
      ranged: false,
    };
  }
  return {
    primaryOp: ">=",
    primaryValue: "",
    secondaryOp: "<=",
    secondaryValue: "",
    ranged: false,
  };
}

function toMetric(draft: Draft): MetricFilter | undefined {
  if (draft.primaryOp === "=") {
    const exact = draft.primaryValue.trim();
    return exact ? { exact } : undefined;
  }
  const filter: MetricFilter = {};
  applyOp(filter, draft.primaryOp, draft.primaryValue.trim());
  if (draft.ranged) {
    applyOp(filter, draft.secondaryOp, draft.secondaryValue.trim());
  }
  if (!filter.lower && !filter.upper) return undefined;
  return filter;
}

function applyOp(filter: MetricFilter, op: PrimaryOp | SecondaryOp, raw: string) {
  if (!raw) return;
  if (op === ">") {
    filter.lowerOp = "gt";
    filter.lower = raw;
  } else if (op === ">=") {
    filter.lowerOp = "gte";
    filter.lower = raw;
  } else if (op === "<") {
    filter.upperOp = "lt";
    filter.upper = raw;
  } else if (op === "<=") {
    filter.upperOp = "lte";
    filter.upper = raw;
  }
}

function oppositeOps(op: PrimaryOp): SecondaryOp[] {
  if (op === "<" || op === "<=") return [">=", ">"];
  return ["<=", "<"];
}

function isOpposite(primary: PrimaryOp, secondary: SecondaryOp): boolean {
  const primaryLower = primary === ">" || primary === ">=";
  const secondaryLower = secondary === ">" || secondary === ">=";
  return primaryLower !== secondaryLower && primary !== "=";
}
