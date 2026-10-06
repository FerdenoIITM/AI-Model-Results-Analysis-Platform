import { useEffect, useRef, useState } from "react";

interface MultiSelectFilterProps {
  label: string;
  options: string[];
  selected: string[];
  onChange: (next: string[]) => void;
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
  const summary = selected.length === 0 ? "All" : selected.join(", ");

  function toggle(option: string) {
    onChange(
      selected.includes(option) ? selected.filter((item) => item !== option) : [...selected, option],
    );
  }

  return (
    <div className="multi-select" ref={rootRef}>
      <span className="multi-select-label">{label}</span>
      <button
        type="button"
        className="multi-select-trigger"
        aria-expanded={open}
        aria-haspopup="listbox"
        onClick={() => setOpen((value) => !value)}
      >
        <span className="multi-select-summary">{summary}</span>
      </button>
      {open ? (
        <div className="multi-select-panel">
          <input
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            placeholder="Search"
            aria-label={`Search ${label}`}
          />
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
