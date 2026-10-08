import { expect, test } from "@playwright/test";
import { ALL_ROWS } from "../support/dataset";
import { forgetTokens } from "../support/http";
import { resetAndSeed, sql } from "../support/seed";
import { signIn, waitForResults } from "./helpers";

const TOTAL = ALL_ROWS.length;

test.use({ viewport: { width: 1280, height: 600 } });

test.beforeAll(async () => {
  await resetAndSeed();
  forgetTokens();
});

test.beforeEach(async ({ page }) => {
  await signIn(page, "superadmin");
  await page.goto("/results");
  await waitForResults(page);
});

test("Submit and Cancel stay reachable in a short viewport", async ({ page }) => {
  await page.getByRole("button", { name: "Submit result" }).click();
  const dialog = page.getByRole("dialog", { name: "Submit model result" });
  await expect(dialog).toBeVisible();
  await expect(dialog.getByRole("button", { name: "Submit", exact: true })).toBeInViewport();
  await expect(dialog.getByRole("button", { name: "Cancel" })).toBeInViewport();
  const box = await dialog.boundingBox();
  expect(box!.y).toBeGreaterThanOrEqual(0);
  expect(box!.y + box!.height).toBeLessThanOrEqual(600);
  const scrolls = await dialog.locator(".modal-body").evaluate((el) => el.scrollHeight > el.clientHeight);
  expect(scrolls).toBe(true);
});

test("Cancel closes the modal without saving", async ({ page }) => {
  await page.getByRole("button", { name: "Submit result" }).click();
  const dialog = page.getByRole("dialog", { name: "Submit model result" });
  await dialog.getByLabel("Model name").fill("ShouldNotExist");
  await dialog.getByRole("button", { name: "Cancel" }).click();
  await expect(dialog).toBeHidden();
  expect(await sql("SELECT 1 FROM results WHERE model_name = 'ShouldNotExist'")).toHaveLength(0);
});

test("submits a result that then appears in the table and the database", async ({ page }) => {
  await page.getByRole("button", { name: "Submit result" }).click();
  const dialog = page.getByRole("dialog", { name: "Submit model result" });
  await dialog.getByLabel("Model name").fill("E2ESubmitted");
  await dialog.getByLabel("Version").fill("9.9.9");
  await dialog.getByLabel("Category", { exact: true }).fill("Dermatology");
  await dialog.getByLabel("Sub category").fill("Melanoma");
  for (const [label, value] of [
    ["Confidence/Accuracy", "96.25"],
    ["Sensitivity", "91"],
    ["Specificity", "92"],
    ["F1 Score", "93"],
    ["Recall", "94"],
    ["Precision", "100"],
  ]) {
    await dialog.getByLabel(label, { exact: true }).fill(value);
  }
  await dialog.locator("textarea").fill('{"source": "e2e", "scores": [0.96]}');
  await dialog.getByRole("button", { name: "Submit", exact: true }).click();

  await expect(dialog).toBeHidden();
  await expect(page.getByText("Result ingested. The worker will vectorize it shortly.")).toBeVisible();
  await waitForResults(page);
  await expect(page.getByRole("heading", { name: `Results (${TOTAL + 1})` })).toBeVisible();
  const row = page.locator(".results-table tbody tr").filter({ hasText: "E2ESubmitted" });
  await expect(row).toContainText("96.25");
  await expect(row).toContainText("pending");

  const [stored] = await sql<{ category: string; confidence_accuracy: number }>(
    "SELECT category, confidence_accuracy FROM results WHERE model_name = 'E2ESubmitted'",
  );
  expect(stored).toEqual({ category: "Dermatology", confidence_accuracy: 96.25 });

  await row.getByRole("button", { name: "View JSON" }).click();
  await expect(page.getByRole("dialog", { name: /E2ESubmitted/ })).toContainText('"source": "e2e"');
});

test("invalid JSON keeps the modal open with an error", async ({ page }) => {
  await page.getByRole("button", { name: "Submit result" }).click();
  const dialog = page.getByRole("dialog", { name: "Submit model result" });
  await dialog.getByLabel("Model name").fill("BadJson");
  await dialog.getByLabel("Version").fill("1");
  await dialog.getByLabel("Category", { exact: true }).fill("C");
  await dialog.getByLabel("Sub category").fill("S");
  for (const label of ["Confidence/Accuracy", "Sensitivity", "Specificity", "F1 Score", "Recall", "Precision"]) {
    await dialog.getByLabel(label, { exact: true }).fill("50");
  }
  await dialog.locator("textarea").fill("{not json");
  await dialog.getByRole("button", { name: "Submit", exact: true }).click();
  await expect(page.getByText("Payload must be valid JSON")).toBeVisible();
  await expect(dialog).toBeVisible();
  expect(await sql("SELECT 1 FROM results WHERE model_name = 'BadJson'")).toHaveLength(0);
});

test("the browser blocks an out-of-range metric", async ({ page }) => {
  await page.getByRole("button", { name: "Submit result" }).click();
  const dialog = page.getByRole("dialog", { name: "Submit model result" });
  const input = dialog.getByLabel("Recall", { exact: true });
  await input.fill("150");
  expect(await input.evaluate((el: HTMLInputElement) => el.validity.rangeOverflow)).toBe(true);
});
