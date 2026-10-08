import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { describe, expect, it, vi } from "vitest";
import { NumericFilter } from "./NumericFilter";
import type { MetricFilter } from "./resultsQuery";

const LABEL = "Confidence/Accuracy";

function setup(initial?: MetricFilter) {
  const onChange = vi.fn();
  function Harness() {
    const [value, setValue] = useState<MetricFilter | undefined>(initial);
    return (
      <>
        <NumericFilter
          label={LABEL}
          value={value}
          onChange={(next) => {
            onChange(next);
            setValue(next);
          }}
        />
        <button type="button" onClick={() => setValue({ lowerOp: "gt", lower: "42" })}>
          external
        </button>
      </>
    );
  }
  const user = userEvent.setup();
  render(<Harness />);
  return {
    user,
    onChange,
    op: () => screen.getByLabelText(`${LABEL} operator`) as HTMLSelectElement,
    value: () => screen.getByLabelText(`${LABEL} value`) as HTMLInputElement,
    op2: () => screen.getByLabelText(`${LABEL} second operator`) as HTMLSelectElement,
    value2: () => screen.getByLabelText(`${LABEL} second value`) as HTMLInputElement,
  };
}

describe("NumericFilter", () => {
  it("renders the label, the operator list and an empty value", () => {
    const { op, value } = setup();
    expect(screen.getByRole("group", { name: LABEL })).toBeInTheDocument();
    expect([...op().options].map((o) => o.value)).toEqual(["=", ">", ">=", "<", "<="]);
    expect(op().value).toBe(">=");
    expect(value().value).toBe("");
    expect(value()).toHaveAttribute("min", "0");
    expect(value()).toHaveAttribute("max", "100");
  });

  it.each([
    ["=", { exact: "95" }],
    [">", { lowerOp: "gt", lower: "95" }],
    [">=", { lowerOp: "gte", lower: "95" }],
    ["<", { upperOp: "lt", upper: "95" }],
    ["<=", { upperOp: "lte", upper: "95" }],
  ])("applies the %s operator on Enter", async (operator, expected) => {
    const { user, onChange, op, value } = setup();
    await user.selectOptions(op(), operator);
    await user.type(value(), "95{Enter}");
    expect(onChange).toHaveBeenCalledTimes(1);
    expect(onChange).toHaveBeenLastCalledWith(expected);
  });

  it("applies the value when the input loses focus", async () => {
    const { user, onChange, value } = setup();
    await user.type(value(), "80");
    expect(onChange).not.toHaveBeenCalled();
    await user.tab();
    expect(onChange).toHaveBeenCalledWith({ lowerOp: "gte", lower: "80" });
  });

  it.each(["0", "100", "99.999", "0.5"])("accepts the boundary or decimal value %s", async (raw) => {
    const { user, onChange, value } = setup();
    await user.type(value(), `${raw}{Enter}`);
    expect(onChange).toHaveBeenCalledWith({ lowerOp: "gte", lower: raw });
    expect(screen.queryByText(/between 0 and 100/)).toBeNull();
  });

  it.each(["101", "100.01", "-1", "-0.01"])("rejects the out-of-range value %s", async (raw) => {
    const { user, onChange, value } = setup();
    await user.type(value(), `${raw}{Enter}`);
    expect(onChange).not.toHaveBeenCalled();
    expect(screen.getByText("Value must be between 0 and 100")).toHaveClass("error");
  });

  it("does nothing for an empty value", async () => {
    const { user, onChange, value } = setup();
    await user.click(value());
    await user.keyboard("{Enter}");
    await user.tab();
    expect(onChange).not.toHaveBeenCalled();
    expect(screen.queryByRole("paragraph")).toBeNull();
  });

  it("ignores non-numeric typing", async () => {
    const { user, onChange, value } = setup();
    await user.type(value(), "abc{Enter}");
    expect(value().value).toBe("");
    expect(onChange).not.toHaveBeenCalled();
  });

  it("does not re-apply an unchanged value", async () => {
    const { user, onChange, value } = setup({ lowerOp: "gte", lower: "95" });
    expect(value().value).toBe("95");
    await user.click(value());
    await user.keyboard("{Enter}");
    await user.tab();
    expect(onChange).not.toHaveBeenCalled();
  });

  it("re-applies immediately when the operator changes on a filled value", async () => {
    const { user, onChange, op } = setup({ lowerOp: "gte", lower: "95" });
    await user.selectOptions(op(), ">");
    expect(onChange).toHaveBeenLastCalledWith({ lowerOp: "gt", lower: "95" });
    await user.selectOptions(op(), "<=");
    expect(onChange).toHaveBeenLastCalledWith({ upperOp: "lte", upper: "95" });
    await user.selectOptions(op(), "=");
    expect(onChange).toHaveBeenLastCalledWith({ exact: "95" });
  });

  it("does not apply when only the operator changes on an empty value", async () => {
    const { user, onChange, op } = setup();
    await user.selectOptions(op(), "<");
    expect(onChange).not.toHaveBeenCalled();
  });

  describe("ranges", () => {
    it("builds a closed range from two bounds", async () => {
      const { user, onChange, value, value2, op2 } = setup();
      await user.type(value(), "90");
      await user.click(screen.getByRole("button", { name: "Add range" }));
      expect([...op2().options].map((o) => o.value)).toEqual(["<=", "<"]);
      await user.type(value2(), "98{Enter}");
      expect(onChange).toHaveBeenLastCalledWith({ lowerOp: "gte", lower: "90", upperOp: "lte", upper: "98" });
    });

    it("builds an exclusive range", async () => {
      const { user, onChange, op, value, op2, value2 } = setup();
      await user.selectOptions(op(), ">");
      await user.type(value(), "90");
      await user.click(screen.getByRole("button", { name: "Add range" }));
      await user.selectOptions(op2(), "<");
      await user.type(value2(), "95{Enter}");
      expect(onChange).toHaveBeenLastCalledWith({ lowerOp: "gt", lower: "90", upperOp: "lt", upper: "95" });
    });

    it("offers lower-bound operators when the first operator is an upper bound", async () => {
      const { user, onChange, op, value, op2, value2 } = setup();
      await user.selectOptions(op(), "<");
      await user.type(value(), "50");
      await user.click(screen.getByRole("button", { name: "Add range" }));
      expect([...op2().options].map((o) => o.value)).toEqual([">=", ">"]);
      await user.type(value2(), "10{Enter}");
      expect(onChange).toHaveBeenLastCalledWith({ upperOp: "lt", upper: "50", lowerOp: "gte", lower: "10" });
    });

    it("rejects a minimum greater than the maximum", async () => {
      const { user, onChange, value, value2 } = setup();
      await user.type(value(), "98");
      await user.click(screen.getByRole("button", { name: "Add range" }));
      await user.type(value2(), "90{Enter}");
      expect(screen.getByText("Minimum is greater than maximum")).toBeInTheDocument();
      expect(onChange).not.toHaveBeenCalledWith(expect.objectContaining({ upper: "90" }));
    });

    it("rejects an exclusive range that matches nothing", async () => {
      const { user, onChange, op, value, op2, value2 } = setup();
      await user.selectOptions(op(), ">");
      await user.type(value(), "90");
      await user.click(screen.getByRole("button", { name: "Add range" }));
      await user.selectOptions(op2(), "<");
      await user.type(value2(), "90{Enter}");
      expect(screen.getByText("Range excludes every value")).toBeInTheDocument();
      expect(onChange).not.toHaveBeenCalledWith(expect.objectContaining({ upperOp: "lt" }));
    });

    it("accepts an inclusive range with equal bounds", async () => {
      const { user, onChange, value, value2 } = setup();
      await user.type(value(), "90");
      await user.click(screen.getByRole("button", { name: "Add range" }));
      await user.type(value2(), "90{Enter}");
      expect(onChange).toHaveBeenLastCalledWith({ lowerOp: "gte", lower: "90", upperOp: "lte", upper: "90" });
    });

    it("rejects an out-of-range second bound", async () => {
      const { user, value, value2 } = setup();
      await user.type(value(), "90");
      await user.click(screen.getByRole("button", { name: "Add range" }));
      await user.type(value2(), "150{Enter}");
      expect(screen.getByText("Value must be between 0 and 100")).toBeInTheDocument();
    });

    it("removes the second bound", async () => {
      const { user, onChange } = setup({ lowerOp: "gte", lower: "90", upperOp: "lte", upper: "98" });
      expect(screen.getByLabelText(`${LABEL} second value`)).toHaveValue(98);
      await user.click(screen.getByRole("button", { name: "Remove range" }));
      expect(onChange).toHaveBeenLastCalledWith({ lowerOp: "gte", lower: "90" });
      expect(screen.queryByLabelText(`${LABEL} second value`)).toBeNull();
    });

    it("drops the range when switching to equals", async () => {
      const { user, onChange, op } = setup({ lowerOp: "gte", lower: "90", upperOp: "lte", upper: "98" });
      await user.selectOptions(op(), "=");
      expect(onChange).toHaveBeenLastCalledWith({ exact: "90" });
      expect(screen.queryByLabelText(`${LABEL} second value`)).toBeNull();
      expect(screen.queryByRole("button", { name: "Add range" })).toBeNull();
    });

    it("hides Add range for the equals operator", async () => {
      const { user, op } = setup();
      expect(screen.getByRole("button", { name: "Add range" })).toBeInTheDocument();
      await user.selectOptions(op(), "=");
      expect(screen.queryByRole("button", { name: "Add range" })).toBeNull();
    });
  });

  it("clears the filter and the draft", async () => {
    const { user, onChange, value } = setup({ upperOp: "lt", upper: "30" });
    await user.click(screen.getByRole("button", { name: "Clear" }));
    expect(onChange).toHaveBeenLastCalledWith(undefined);
    expect(value().value).toBe("");
    expect(screen.queryByRole("button", { name: "Clear" })).toBeNull();
  });

  it("shows Clear for a typed but unapplied draft", async () => {
    const { user, value } = setup();
    expect(screen.queryByRole("button", { name: "Clear" })).toBeNull();
    await user.type(value(), "5");
    expect(screen.getByRole("button", { name: "Clear" })).toBeInTheDocument();
  });

  it("resets the draft and error when the applied value changes from outside", async () => {
    const { user, op, value } = setup();
    await user.type(value(), "500{Enter}");
    expect(screen.getByText("Value must be between 0 and 100")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "external" }));
    expect(op().value).toBe(">");
    expect(value().value).toBe("42");
    expect(screen.queryByText("Value must be between 0 and 100")).toBeNull();
  });

  it.each([
    [{ exact: "7" }, "=", "7"],
    [{ lowerOp: "gt", lower: "1" }, ">", "1"],
    [{ upperOp: "lte", upper: "2" }, "<=", "2"],
  ] as const)("shows the applied filter %j", (initial, operator, raw) => {
    const { op, value } = setup({ ...initial });
    expect(op().value).toBe(operator);
    expect(value().value).toBe(raw);
  });
});
