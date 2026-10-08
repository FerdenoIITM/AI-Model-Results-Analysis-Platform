import { expect, type Locator, type Page } from "@playwright/test";
import { ACCOUNTS, TEST_PASSWORD, type AccountRole } from "../support/env";
import { tokenFor } from "../support/http";

/** Signs in through the real login form. */
export async function loginViaUi(page: Page, role: AccountRole) {
  await page.goto("/login");
  await page.getByLabel("Email").fill(ACCOUNTS[role].email);
  await page.getByLabel("Password").fill(TEST_PASSWORD);
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByRole("heading", { name: "Dashboard" })).toBeVisible();
}

/** Starts the page with a real token already stored, skipping the login form. */
export async function signIn(page: Page, role: AccountRole) {
  const token = await tokenFor(role);
  await page.addInitScript((value) => localStorage.setItem("amrap_token", value), token);
}

export function nav(page: Page): Locator {
  return page.getByRole("navigation");
}

export function multiSelect(page: Page, label: string): Locator {
  return page
    .locator(".multi-select")
    .filter({ has: page.locator(".multi-select-label", { hasText: new RegExp(`^${label}$`) }) });
}

export async function pickOptions(page: Page, label: string, ...options: string[]) {
  const root = multiSelect(page, label);
  await root.getByRole("button").click();
  for (const option of options) {
    await reloadsResults(page, () => root.getByRole("checkbox", { name: option, exact: true }).click());
  }
  await page.keyboard.press("Escape");
}

/**
 * Runs an action that triggers a results reload and waits for that list response to render.
 * The page briefly shows the new page number with the old rows before loading starts,
 * so waiting on the pager text alone is not enough.
 */
export async function reloadsResults(page: Page, action: () => Promise<unknown>) {
  const response = page.waitForResponse(
    (r) => r.request().method() === "GET" && new URL(r.url()).pathname === "/api/results",
  );
  await action();
  await response;
  await waitForResults(page);
}

export async function waitForResults(page: Page) {
  await expect(page.locator(".results-table-wrap.is-loading")).toHaveCount(0);
  await expect(page.getByText(/^Page \d+ of \d+$/)).toBeVisible();
}

export function resultRows(page: Page): Locator {
  return page.locator(".results-table tbody tr").filter({ hasNot: page.locator(".cell-empty") });
}

/** Text of one column (0-based) for every visible row. */
export async function columnText(page: Page, index: number): Promise<string[]> {
  return resultRows(page).locator(`td:nth-child(${index + 1})`).allTextContents();
}

/** Model + version, which is unique across the seeded dataset (iteration ids share a second). */
export async function rowKeys(page: Page): Promise<string[]> {
  const models = await columnText(page, COL.model);
  const versions = await columnText(page, COL.version);
  return models.map((m, i) => `${m}@${versions[i]}`);
}

export function optionLabels(page: Page, label: string): Locator {
  return multiSelect(page, label).locator(".multi-select-option");
}

export const COL = { model: 0, version: 1, category: 3, subCategory: 4, confidence: 5, sensitivity: 6 };
