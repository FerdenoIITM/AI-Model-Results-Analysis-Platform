import { useEffect, useRef, useState } from "react";

interface MultiSelectFilterProps {
  label: string;
  options: string[];
  selected: string[];
  onChange: (next: string[]) => void;
}

function summaryLabel(selected: string[]): string {
  if (selected.length === 0) return "All";
  if (selected.length === 1) return selected[0];
  return `${selected.length} selected`;
}

export function MultiSelectFilter({ label, options, selected, onChange }: MultiSelectFilterProps) {
  const [open, setOpen] = useState(false);
  const [search, setSearch] = useState("");
  const rootRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    function onPointer(event: MouseEvent) {
      if (!rootRef.current?.contains(event.target as Node)) {
        setOpen(false);
      }
    }
    function onKey(event: KeyboardEvent) {
      if (event.key === "Escape") setOpen(false);
    }
    document.addEventListener("mousedown", onPointer);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("mousedown", onPointer);
      document.removeEventListener("keydown", onKey);
    };
  }, [open]);

  const query = search.trim().toLowerCase();
  const visible = options.filter((option) => option.toLowerCase().includes(query));

  function toggle(option: string) {
    onChange(
      selected.includes(option) ? selected.filter((item) => item !== option) : [...selected, option],
    );
  }

  function remove(option: string) {
    onChange(selected.filter((item) => item !== option));
  }

  function selectAllVisible() {
    const next = [...selected];
    for (const option of visible) {
      if (!next.includes(option)) next.push(option);
    }
    onChange(next);
  }

  function deselectAll() {
    if (query) {
      const drop = new Set(visible);
      onChange(selected.filter((item) => !drop.has(item)));
      return;
    }
    onChange([]);
  }

  const allVisibleSelected =
    visible.length > 0 && visible.every((option) => selected.includes(option));
  const anyVisibleSelected = visible.some((option) => selected.includes(option));

  return (
    <div className="multi-select" ref={rootRef}>
      <span className="multi-select-label">{label}</span>
      <button
        type="button"
        className="multi-select-trigger"
        aria-label={label}
        aria-expanded={open}
        aria-haspopup="listbox"
        onClick={() => setOpen((value) => !value)}
      >
        <span className="multi-select-summary">{summaryLabel(selected)}</span>
      </button>
      {selected.length > 0 ? (
        <ul className="multi-select-chips" aria-label={`Selected ${label}`}>
          {selected.map((option) => (
            <li key={option}>
              <span className="multi-select-chip">
                <span className="multi-select-chip-text">{option}</span>
                <button
                  type="button"
                  className="multi-select-chip-remove"
                  aria-label={`Remove ${option}`}
                  onClick={(event) => {
                    event.stopPropagation();
                    remove(option);
                  }}
                >
                  ×
                </button>
              </span>
            </li>
          ))}
        </ul>
      ) : null}
      {open ? (
        <div className="multi-select-panel">
          <input
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            placeholder="Search"
            aria-label={`Search ${label}`}
          />
          <div className="multi-select-actions">
            <button
              type="button"
              className="btn ghost multi-select-action"
              disabled={visible.length === 0 || allVisibleSelected}
              onClick={selectAllVisible}
            >
              Select all
            </button>
            <button
              type="button"
              className="btn ghost multi-select-action"
              disabled={!anyVisibleSelected && selected.length === 0}
              onClick={deselectAll}
            >
              Deselect all
            </button>
          </div>
          <ul role="listbox" aria-label={label} aria-multiselectable="true">
            {visible.map((option) => (
              <li key={option}>
                <label className="multi-select-option">
                  <input
                    type="checkbox"
                    checked={selected.includes(option)}
                    onChange={() => toggle(option)}
                  />
                  {option}
                </label>
              </li>
            ))}
            {visible.length === 0 ? <li className="muted">No matches</li> : null}
          </ul>
        </div>
      ) : null}
    </div>
  );
}
