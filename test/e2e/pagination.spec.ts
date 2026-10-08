import { expect, test } from "@playwright/test";
import { ALL_ROWS } from "../support/dataset";
import { forgetTokens } from "../support/http";
import { resetAndSeed } from "../support/seed";
import { COL, columnText, pickOptions, reloadsResults, resultRows, rowKeys, signIn, waitForResults } from "./helpers";

const TOTAL = ALL_ROWS.length;

test.beforeAll(async () => {
  await resetAndSeed();
  forgetTokens();
});

test.beforeEach(async ({ page }) => {
  await signIn(page, "member");
});

test("pages through all results with the default page size", async ({ page }) => {
  await page.goto("/results");
  await waitForResults(page);
  const pages = Math.ceil(TOTAL / 50);
  const next = page.getByRole("button", { name: "Next" });
  const previous = page.getByRole("button", { name: "Previous" });
  await expect(page.getByText(`Page 1 of ${pages}`)).toBeVisible();
  await expect(previous).toBeDisabled();
  await expect(resultRows(page)).toHaveCount(50);
  const firstPage = await rowKeys(page);

  await reloadsResults(page, () => next.click());
  await expect(page.getByText(`Page 2 of ${pages}`)).toBeVisible();
  const secondPage = await rowKeys(page);
  expect(secondPage).toHaveLength(50);
  expect(secondPage.filter((id) => firstPage.includes(id))).toEqual([]);

  await reloadsResults(page, () => next.click());
  await expect(resultRows(page)).toHaveCount(TOTAL - 100);
  await expect(next).toBeDisabled();
  const thirdPage = await rowKeys(page);
  expect(new Set([...firstPage, ...secondPage, ...thirdPage]).size).toBe(TOTAL);

  await reloadsResults(page, () => previous.click());
  expect(await rowKeys(page)).toEqual(secondPage);
});

test("changing the page size returns to page 1", async ({ page }) => {
  await page.goto("/results?page=3");
  await waitForResults(page);
  await reloadsResults(page, () => page.getByLabel("Page size").selectOption("100"));
  await expect(page.getByText(`Page 1 of ${Math.ceil(TOTAL / 100)}`)).toBeVisible();
  await expect(resultRows(page)).toHaveCount(100);
  await reloadsResults(page, () => page.getByLabel("Page size").selectOption("25"));
  await expect(page.getByText(`Page 1 of ${Math.ceil(TOTAL / 25)}`)).toBeVisible();
  await expect(resultRows(page)).toHaveCount(25);
});

test("paging 120 bulk rows keeps the filter and sort", async ({ page }) => {
  await page.goto("/results?pageSize=25&sort=-confidenceAccuracy");
  await waitForResults(page);
  await page.getByRole("button", { name: /^Filters/ }).click();
  await pickOptions(page, "Model", "BulkModel");
  await expect(page.getByRole("heading", { name: "Results (120)" })).toBeVisible();
  await expect(page.getByText("Page 1 of 5")).toBeVisible();
  const seen: number[] = [];
  for (let p = 1; p <= 5; p++) {
    seen.push(...(await columnText(page, COL.confidence)).map(Number));
    if (p < 5) await reloadsResults(page, () => page.getByRole("button", { name: "Next" }).click());
  }
  expect(seen).toHaveLength(120);
  expect(seen).toEqual([...seen].sort((a, b) => b - a));
  await expect(page).toHaveURL(/page=5/);
  await expect(page).toHaveURL(/model=BulkModel/);
});

test("a page number past the end moves back to the last page", async ({ page }) => {
  await page.goto("/results?page=99");
  await expect(page).toHaveURL(new RegExp(`page=${Math.ceil(TOTAL / 50)}`));
  await waitForResults(page);
  await expect(resultRows(page)).toHaveCount(TOTAL - 100);
});
