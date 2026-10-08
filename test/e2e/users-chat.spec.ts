import { expect, test } from "@playwright/test";
import { ACCOUNTS } from "../support/env";
import { forgetTokens } from "../support/http";
import { resetAndSeed, sql } from "../support/seed";
import { signIn } from "./helpers";

test.beforeAll(async () => {
  await resetAndSeed();
  forgetTokens();
});

test("superadmin creates, edits and deletes a user", async ({ page }) => {
  await signIn(page, "superadmin");
  await page.goto("/users");
  await expect(page.getByRole("table")).toBeVisible();

  await page.getByRole("button", { name: "Create user" }).click();
  const create = page.getByRole("dialog", { name: "Create user" });
  await create.getByLabel("Display name").fill("E2E Person");
  await create.getByLabel("Email").fill("e2e.person@amrap.test");
  await create.getByLabel("Password").fill("Password123!");
  await create.getByLabel("Role").selectOption("member");
  await create.getByRole("button", { name: "Create" }).click();
  await expect(create).toBeHidden();
  const row = page.getByRole("row").filter({ hasText: "e2e.person@amrap.test" });
  await expect(row).toContainText("E2E Person");
  await expect(row).toContainText("member");

  await row.getByRole("button", { name: "Edit" }).click();
  const edit = page.getByRole("dialog", { name: "Edit user" });
  await edit.getByLabel("Display name").fill("E2E Renamed");
  await edit.getByRole("button", { name: "Save" }).click();
  await expect(edit).toBeHidden();
  await expect(row).toContainText("E2E Renamed");

  await row.getByRole("button", { name: "Edit" }).click();
  await edit.getByRole("button", { name: "Delete user" }).click();
  const confirm = page.getByRole("dialog", { name: "Confirm deletion" });
  await expect(confirm).toContainText("E2E Renamed");
  await confirm.getByRole("button", { name: "Yes, delete" }).click();
  await expect(confirm).toBeHidden();
  await expect(page.getByRole("row").filter({ hasText: "e2e.person@amrap.test" })).toHaveCount(0);
  expect(await sql("SELECT 1 FROM users WHERE email = 'e2e.person@amrap.test'")).toHaveLength(0);
});

test("a duplicate email shows the conflict and keeps the dialog open", async ({ page }) => {
  await signIn(page, "admin");
  await page.goto("/users");
  await page.getByRole("button", { name: "Create user" }).click();
  const create = page.getByRole("dialog", { name: "Create user" });
  await expect(create.getByLabel("Role").locator("option")).toHaveText(["member"]);
  await create.getByLabel("Display name").fill("Copy");
  await create.getByLabel("Email").fill(ACCOUNTS.member.email);
  await create.getByLabel("Password").fill("Password123!");
  await create.getByRole("button", { name: "Create" }).click();
  await expect(page.locator("p.error")).toBeVisible();
  await expect(create).toBeVisible();
});

test("chat shows an error when the language model is down, and Clear resets it", async ({ page }) => {
  await signIn(page, "member");
  await page.goto("/chat");
  await page.getByPlaceholder("Ask a question about your results…").fill("Which model is best?");
  await page.getByRole("button", { name: "Send" }).click();
  const errorBubble = page.locator(".chat-bubble.assistant.error");
  await expect(errorBubble).toBeVisible({ timeout: 30_000 });
  await expect(errorBubble).toContainText(/failed/i);
  await expect(page.locator(".chat-bubble.user")).toHaveText("Which model is best?");
  await page.getByRole("button", { name: "Clear" }).click();
  await expect(page.locator(".chat-bubble")).toHaveCount(0);
  await expect(page.getByText("Try one of these to get started:")).toBeVisible();
});
