import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { describe, expect, it, vi } from "vitest";
import { MultiSelectFilter } from "./MultiSelectFilter";

const OPTIONS = ["CardioNet", "ChestScan", "NeuroSlice"];

function setup(initial: string[] = [], options: string[] = OPTIONS) {
  const onChange = vi.fn();
  function Harness() {
    const [selected, setSelected] = useState(initial);
    return (
      <div>
        <MultiSelectFilter
          label="Model"
          options={options}
          selected={selected}
          onChange={(next) => {
            onChange(next);
            setSelected(next);
          }}
        />
        <p>outside</p>
      </div>
    );
  }
  const user = userEvent.setup();
  render(<Harness />);
  return { user, onChange, trigger: () => screen.getByRole("button") };
}

describe("MultiSelectFilter", () => {
  it("summarises no selection as All and starts closed", () => {
    const { trigger } = setup();
    expect(screen.getByText("Model")).toBeInTheDocument();
    expect(trigger()).toHaveTextContent("All");
    expect(trigger()).toHaveAttribute("aria-expanded", "false");
    expect(screen.queryByRole("listbox")).toBeNull();
  });

  it("lists every option when opened", async () => {
    const { user, trigger } = setup();
    await user.click(trigger());
    expect(trigger()).toHaveAttribute("aria-expanded", "true");
    expect(screen.getAllByRole("checkbox").map((c) => c.parentElement?.textContent)).toEqual(OPTIONS);
  });

  it("adds and removes selections", async () => {
    const { user, onChange, trigger } = setup();
    await user.click(trigger());
    await user.click(screen.getByRole("checkbox", { name: "NeuroSlice" }));
    expect(onChange).toHaveBeenLastCalledWith(["NeuroSlice"]);
    await user.click(screen.getByRole("checkbox", { name: "CardioNet" }));
    expect(onChange).toHaveBeenLastCalledWith(["NeuroSlice", "CardioNet"]);
    expect(trigger()).toHaveTextContent("NeuroSlice, CardioNet");
    await user.click(screen.getByRole("checkbox", { name: "NeuroSlice" }));
    expect(onChange).toHaveBeenLastCalledWith(["CardioNet"]);
    expect(screen.getByRole("checkbox", { name: "CardioNet" })).toBeChecked();
    expect(screen.getByRole("checkbox", { name: "NeuroSlice" })).not.toBeChecked();
  });

  it("shows existing selections as checked", async () => {
    const { user, trigger } = setup(["ChestScan"]);
    expect(trigger()).toHaveTextContent("ChestScan");
    await user.click(trigger());
    expect(screen.getByRole("checkbox", { name: "ChestScan" })).toBeChecked();
  });

  it("filters options by case-insensitive search", async () => {
    const { user, trigger } = setup();
    await user.click(trigger());
    await user.type(screen.getByLabelText("Search Model"), "  NEURO ");
    expect(screen.getAllByRole("checkbox")).toHaveLength(1);
    expect(screen.getByRole("checkbox", { name: "NeuroSlice" })).toBeInTheDocument();
  });

  it("says when the search matches nothing", async () => {
    const { user, trigger } = setup();
    await user.click(trigger());
    await user.type(screen.getByLabelText("Search Model"), "zzz");
    expect(screen.queryAllByRole("checkbox")).toHaveLength(0);
    expect(screen.getByText("No matches")).toBeInTheDocument();
  });

  it("shows No matches when there are no options at all", async () => {
    const { user, trigger } = setup([], []);
    await user.click(trigger());
    expect(screen.getByText("No matches")).toBeInTheDocument();
  });

  it("closes on Escape, outside click and a second trigger click", async () => {
    const { user, trigger } = setup();
    await user.click(trigger());
    await user.keyboard("{Escape}");
    expect(screen.queryByRole("listbox")).toBeNull();

    await user.click(trigger());
    await user.click(screen.getByText("outside"));
    expect(screen.queryByRole("listbox")).toBeNull();

    await user.click(trigger());
    await user.click(trigger());
    expect(screen.queryByRole("listbox")).toBeNull();
  });

  it("stays open while clicking inside the panel", async () => {
    const { user, trigger } = setup();
    await user.click(trigger());
    await user.click(screen.getByLabelText("Search Model"));
    await user.click(screen.getByRole("checkbox", { name: "ChestScan" }));
    expect(screen.getByRole("listbox", { name: "Model" })).toBeInTheDocument();
  });

  it("handles option names with special characters", async () => {
    const { user, onChange, trigger } = setup([], ["A & B", "C/D (v2)", "Ünïcode"]);
    await user.click(trigger());
    await user.click(screen.getByRole("checkbox", { name: "C/D (v2)" }));
    expect(onChange).toHaveBeenLastCalledWith(["C/D (v2)"]);
    await user.type(screen.getByLabelText("Search Model"), "ünï");
    expect(screen.getByRole("checkbox", { name: "Ünïcode" })).toBeInTheDocument();
  });
});
