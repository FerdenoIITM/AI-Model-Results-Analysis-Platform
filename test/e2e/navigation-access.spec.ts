import { expect, test } from "@playwright/test";
import { ACCOUNTS, TEST_PASSWORD } from "../support/env";
import { forgetTokens } from "../support/http";
import { resetAndSeed } from "../support/seed";
import { loginViaUi, nav, signIn } from "./helpers";

test.beforeAll(async () => {
  await resetAndSeed();
  forgetTokens();
});

const PAGES = [
  ["Dashboard", "/", "Dashboard"],
  ["Results", "/results", "Model Results"],
  ["Chat", "/chat", "Ask AMRAP"],
  ["User Management", "/users", "User Management"],
] as const;

test("highlights only the active navigation item", async ({ page }) => {
  await signIn(page, "superadmin");
  await page.goto("/");
  for (const [link, path, heading] of PAGES) {
    await nav(page).getByRole("link", { name: link }).click();
    await expect(page).toHaveURL(new RegExp(`${path}$`));
    await expect(page.getByRole("heading", { name: heading, level: 1 })).toBeVisible();
    for (const [other] of PAGES) {
      const locator = nav(page).getByRole("link", { name: other });
      if (other === link) {
        await expect(locator).toHaveClass(/\bactive\b/);
        await expect(locator).toHaveAttribute("aria-current", "page");
      } else {
        await expect(locator).not.toHaveClass(/\bactive\b/);
      }
    }
  }
});

test("members see no User Management link and are sent away from /users", async ({ page }) => {
  await signIn(page, "member");
  await page.goto("/");
  await expect(nav(page).getByRole("link")).toHaveText(["Dashboard", "Results", "Chat"]);
  await page.goto("/users");
  await expect(page).toHaveURL(/\/$/);
  await expect(page.getByRole("heading", { name: "Dashboard" })).toBeVisible();
});

test("anonymous visitors are sent to the login page", async ({ page }) => {
  for (const path of ["/", "/results", "/users", "/chat"]) {
    await page.goto(path);
    await expect(page).toHaveURL(/\/login$/);
  }
});

test("wrong password and deactivated account show errors", async ({ page }) => {
  await page.goto("/login");
  await page.getByLabel("Email").fill(ACCOUNTS.admin.email);
  await page.getByLabel("Password").fill("Wrong-Password-1");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.locator("p.error")).toBeVisible();
  await expect(page).toHaveURL(/\/login$/);

  await page.getByLabel("Email").fill(ACCOUNTS.inactive.email);
  await page.getByLabel("Password").fill(TEST_PASSWORD);
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.locator("p.error")).toBeVisible();
  await expect(page).toHaveURL(/\/login$/);
});

test("logging out returns to the login page and protects the app again", async ({ page }) => {
  await loginViaUi(page, "admin");
  await page.getByRole("button", { name: "Log out" }).click();
  await expect(page).toHaveURL(/\/login$/);
  await page.goto("/results");
  await expect(page).toHaveURL(/\/login$/);
});

test("an unknown route goes to the dashboard", async ({ page }) => {
  await signIn(page, "member");
  await page.goto("/no/such/page");
  await expect(page).toHaveURL(/\/$/);
});
