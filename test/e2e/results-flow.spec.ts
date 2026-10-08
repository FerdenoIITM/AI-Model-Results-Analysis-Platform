import { expect, test } from "@playwright/test";
import { ALL_ROWS } from "../support/dataset";
import { forgetTokens } from "../support/http";
import { resetAndSeed, sql } from "../support/seed";
import {
  COL,
  columnText,
  loginViaUi,
  multiSelect,
  nav,
  optionLabels,
  pickOptions,
  reloadsResults,
  resultRows,
  waitForResults,
} from "./helpers";

const TOTAL = ALL_ROWS.length;

test.beforeAll(async () => {
  await resetAndSeed();
  forgetTokens();
});

test("model, category and confidence filters, sorting, then clearing", async ({ page }) => {
  await loginViaUi(page, "member");
  await nav(page).getByRole("link", { name: "Results" }).click();
  await waitForResults(page);
  await expect(page.getByRole("heading", { name: `Results (${TOTAL})` })).toBeVisible();

  await page.getByRole("button", { name: /^Filters/ }).click();

  await test.step("model options come from the database", async () => {
    await multiSelect(page, "Model").getByRole("button").click();
    await expect(multiSelect(page, "Model").getByRole("checkbox")).toHaveCount(4);
    await page.keyboard.press("Escape");
  });

  await test.step("select the model", async () => {
    await pickOptions(page, "Model", "CardioNet");
    await expect(page).toHaveURL(/model=CardioNet/);
    expect(new Set(await columnText(page, COL.model))).toEqual(new Set(["CardioNet"]));
  });

  await test.step("apply the category", async () => {
    await pickOptions(page, "Category", "Cardiology");
    expect(new Set(await columnText(page, COL.category))).toEqual(new Set(["Cardiology"]));
  });

  await test.step("apply Confidence >= 95", async () => {
    await page.getByLabel("Confidence/Accuracy operator").selectOption(">=");
    await page.getByLabel("Confidence/Accuracy value").fill("95");
    await reloadsResults(page, () => page.getByLabel("Confidence/Accuracy value").press("Enter"));
    await expect(page).toHaveURL(/confidenceAccuracyGte=95/);
    await expect(page.getByRole("button", { name: /^Filters/ })).toContainText("3");
  });

  await test.step("the table matches the database", async () => {
    const expected = await sql<{ version: string }>(
      `SELECT version FROM results
        WHERE model_name = 'CardioNet' AND category = 'Cardiology' AND confidence_accuracy >= 95`,
    );
    await expect(page.getByRole("heading", { name: `Results (${expected.length})` })).toBeVisible();
    await expect(resultRows(page)).toHaveCount(expected.length);
    expect((await columnText(page, COL.version)).sort()).toEqual(expected.map((r) => r.version).sort());
  });

  await test.step("sort confidence numerically, both directions", async () => {
    const header = page.getByRole("button", { name: "Confidence/Accuracy", exact: true });
    await reloadsResults(page, () => header.click());
    expect(await columnText(page, COL.confidence)).toEqual(["95.00", "97.00", "99.50", "100.00"]);
    await reloadsResults(page, () => header.click());
    expect(await columnText(page, COL.confidence)).toEqual(["100.00", "99.50", "97.00", "95.00"]);
    await expect(page.locator("th[aria-sort='descending']")).toContainText("Confidence/Accuracy");
  });

  await test.step("clear filters shows every result again", async () => {
    await reloadsResults(page, () => page.getByRole("button", { name: "Clear filters" }).click());
    await expect(page.getByRole("heading", { name: `Results (${TOTAL})` })).toBeVisible();
    await expect(page.getByLabel("Confidence/Accuracy value")).toHaveValue("");
    await expect(multiSelect(page, "Model").getByRole("button")).toHaveText("All");
    await expect(multiSelect(page, "Category").getByRole("button")).toHaveText("All");
    await expect(page.getByRole("button", { name: "Clear filters" })).toBeDisabled();
    expect(new URL(page.url()).searchParams.has("model")).toBe(false);
  });
});

test("a combination with no matches shows the empty state", async ({ page }) => {
  await loginViaUi(page, "member");
  await page.goto("/results?model=ChestScan&category=Cardiology");
  await waitForResults(page);
  await expect(page.getByText("No results match the current filters.")).toBeVisible();
  await expect(page.getByRole("heading", { name: "Results (0)" })).toBeVisible();
});

test("an inverted range is refused in the browser", async ({ page }) => {
  await loginViaUi(page, "member");
  await page.goto("/results");
  await waitForResults(page);
  await page.getByRole("button", { name: /^Filters/ }).click();
  const group = page.getByRole("group", { name: "Sensitivity" });
  await page.getByLabel("Sensitivity value").fill("98");
  await reloadsResults(page, () => group.getByRole("button", { name: "Add range" }).click());
  await page.getByLabel("Sensitivity second value").fill("90");
  await page.getByLabel("Sensitivity second value").press("Enter");
  await expect(group.getByText("Minimum is greater than maximum")).toBeVisible();
  expect(new URL(page.url()).searchParams.has("sensitivityLte")).toBe(false);
});

test("category selection narrows the sub categories", async ({ page }) => {
  await loginViaUi(page, "member");
  await page.goto("/results");
  await waitForResults(page);
  await page.getByRole("button", { name: /^Filters/ }).click();
  await pickOptions(page, "Category", "Neurology");
  await multiSelect(page, "Sub Category").getByRole("button").click();
  await expect(optionLabels(page, "Sub Category")).toHaveText(["Stroke", "Tumor"]);
});
