import { screen, waitFor, within } from "@testing-library/react";
import type { UserEvent } from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import type { UserRole } from "../../shared/api/types";
import { renderApp } from "../../test/render";
import { API, db, server } from "../../test/server";

async function openUsers(as: UserRole = "superadmin") {
  const rendered = renderApp("/users", { as });
  await screen.findByRole("heading", { name: "User Management" });
  await screen.findByRole("table");
  return rendered;
}

function rowFor(name: string) {
  const [, body] = within(screen.getByRole("table")).getAllByRole("rowgroup");
  const row = within(body)
    .getAllByRole("row")
    .find((r) => within(r).getAllByRole("cell")[0].textContent === name);
  if (!row) throw new Error(`No row for ${name}`);
  return within(row);
}

function names(): string[] {
  const [, body] = within(screen.getByRole("table")).getAllByRole("rowgroup");
  return within(body)
    .getAllByRole("row")
    .map((row) => within(row).getAllByRole("cell")[0].textContent ?? "");
}

async function editUser(user: UserEvent, name: string) {
  await user.click(rowFor(name).getByRole("button", { name: "Edit" }));
  return within(screen.getByRole("dialog", { name: "Edit user" }));
}

describe("UsersPage", () => {
  describe("list", () => {
    it("shows a loading state, then every other user to the superadmin", async () => {
      server.use(
        http.get(`${API}/users`, async () => {
          await new Promise((resolve) => setTimeout(resolve, 150));
          return HttpResponse.json(db.users.filter((u) => u.id !== "u-super"));
        }),
      );
      renderApp("/users", { as: "superadmin" });
      await screen.findByRole("heading", { name: "User Management" });
      expect(screen.getByText("Loading…")).toBeInTheDocument();
      await screen.findByRole("table");
      expect(names()).toEqual(["Alice Admin", "Bob Admin", "Mia Member", "Max Member", "Otto Other"]);
      expect(rowFor("Alice Admin").getByRole("cell", { name: "admin@amrap.test" })).toBeInTheDocument();
      expect(rowFor("Alice Admin").getByRole("cell", { name: "Super Admin" })).toBeInTheDocument();
      expect(rowFor("Mia Member").getByRole("cell", { name: "Alice Admin" })).toBeInTheDocument();
      expect(screen.getAllByRole("button", { name: "Edit" })).toHaveLength(5);
    });

    it("shows a dash when the creator is unknown", async () => {
      db.users = db.users.map((u) => (u.id === "u-member3" ? { ...u, createdBy: null } : u));
      await openUsers();
      expect(rowFor("Otto Other").getAllByRole("cell")[4]).toHaveTextContent("—");
    });

    it("shows an admin only their own members, all editable", async () => {
      await openUsers("admin");
      expect(names()).toEqual(["Mia Member", "Max Member"]);
      expect(rowFor("Mia Member").getByRole("button", { name: "Edit" })).toBeInTheDocument();
      expect(rowFor("Max Member").getByRole("button", { name: "Edit" })).toBeInTheDocument();
      expect(rowFor("Mia Member").getAllByRole("cell")[4]).toHaveTextContent("Alice Admin");
    });

    it("shows no edit action for an account the admin does not own", async () => {
      server.use(http.get(`${API}/users`, () => HttpResponse.json(db.users.filter((u) => u.id !== "u-admin"))));
      await openUsers("admin");
      expect(rowFor("Otto Other").queryByRole("button", { name: "Edit" })).toBeNull();
      expect(rowFor("Bob Admin").queryByRole("button", { name: "Edit" })).toBeNull();
      expect(rowFor("Mia Member").getByRole("button", { name: "Edit" })).toBeInTheDocument();
    });

    it("shows inactive users as inactive", async () => {
      db.users = db.users.map((u) => (u.id === "u-member" ? { ...u, active: false } : u));
      await openUsers();
      expect(rowFor("Mia Member").getAllByRole("cell")[3]).toHaveTextContent("no");
      expect(rowFor("Max Member").getAllByRole("cell")[3]).toHaveTextContent("yes");
    });

    it("shows the last login and created dates on hover", async () => {
      db.users = db.users.map((u) => (u.id === "u-member" ? { ...u, lastLoginAt: "2026-02-01T10:00:00Z" } : u));
      const { user } = await openUsers();
      await user.hover(rowFor("Mia Member").getByText(/2026/));
      const tip = await screen.findByRole("tooltip");
      expect(tip).toHaveTextContent("Last login");
      expect(tip).toHaveTextContent("Created");
      expect(tip).not.toHaveTextContent("—");
      await user.unhover(rowFor("Mia Member").getByText(/2026/));
      expect(screen.queryByRole("tooltip")).toBeNull();
    });

    it("shows the error when the list cannot be loaded", async () => {
      server.use(http.get(`${API}/users`, () => HttpResponse.json({ message: "Unexpected server error" }, { status: 500 })));
      renderApp("/users", { as: "superadmin" });
      expect(await screen.findByText("Unexpected server error")).toHaveClass("error");
    });
  });

  describe("create", () => {
    async function openCreate(as: UserRole = "superadmin") {
      const rendered = await openUsers(as);
      await rendered.user.click(screen.getByRole("button", { name: "Create user" }));
      return { ...rendered, dialog: within(screen.getByRole("dialog", { name: "Create user" })) };
    }

    it("offers admin and member to the superadmin, defaulting to admin", async () => {
      const { dialog } = await openCreate("superadmin");
      const role = dialog.getByLabelText("Role") as HTMLSelectElement;
      expect([...role.options].map((o) => o.value)).toEqual(["admin", "member"]);
      expect(role.value).toBe("admin");
    });

    it("offers only member to an admin", async () => {
      const { dialog } = await openCreate("admin");
      const role = dialog.getByLabelText("Role") as HTMLSelectElement;
      expect([...role.options].map((o) => o.value)).toEqual(["member"]);
    });

    it("marks fields required with an email type and an 8 character minimum password", async () => {
      const { dialog } = await openCreate();
      expect(dialog.getByLabelText("Display name")).toBeRequired();
      expect(dialog.getByLabelText("Email")).toHaveAttribute("type", "email");
      expect(dialog.getByLabelText("Password")).toHaveAttribute("minlength", "8");
    });

    it("creates a user and shows them in the list", async () => {
      const { user, dialog } = await openCreate();
      await user.type(dialog.getByLabelText("Display name"), "Nina New");
      await user.type(dialog.getByLabelText("Email"), "nina@amrap.test");
      await user.type(dialog.getByLabelText("Password"), "Password123!");
      await user.selectOptions(dialog.getByLabelText("Role"), "member");
      await user.click(dialog.getByRole("button", { name: "Create" }));
      await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
      expect(db.userWrites).toEqual([
        { method: "POST", body: { email: "nina@amrap.test", password: "Password123!", displayName: "Nina New", role: "member" } },
      ]);
      await waitFor(() => expect(names()).toContain("Nina New"));
    });

    it("keeps the dialog open and shows the conflict for a duplicate email", async () => {
      const { user, dialog } = await openCreate();
      await user.type(dialog.getByLabelText("Display name"), "Copy");
      await user.type(dialog.getByLabelText("Email"), "MEMBER@amrap.test");
      await user.type(dialog.getByLabelText("Password"), "Password123!");
      await user.click(dialog.getByRole("button", { name: "Create" }));
      expect(await screen.findByText("Email already exists")).toBeInTheDocument();
      expect(screen.getByRole("dialog", { name: "Create user" })).toBeInTheDocument();
      expect(dialog.getByRole("button", { name: "Create" })).toBeEnabled();
    });

    it("closes on Cancel without creating", async () => {
      const { user, dialog } = await openCreate();
      await user.type(dialog.getByLabelText("Display name"), "Nobody");
      await user.click(dialog.getByRole("button", { name: "Cancel" }));
      expect(screen.queryByRole("dialog")).toBeNull();
      expect(db.userWrites).toEqual([]);
    });
  });

  describe("edit", () => {
    it("saves name and email without sending an empty password or an unchanged role", async () => {
      const { user } = await openUsers();
      const dialog = await editUser(user, "Mia Member");
      const name = dialog.getByLabelText("Display name");
      await user.clear(name);
      await user.type(name, "Mia Renamed");
      await user.click(dialog.getByRole("button", { name: "Save" }));
      await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
      expect(db.userWrites).toEqual([
        { method: "PATCH", id: "u-member", body: { displayName: "Mia Renamed", email: "member@amrap.test", active: true } },
      ]);
      await waitFor(() => expect(names()).toContain("Mia Renamed"));
    });

    it("lets the superadmin change role, password and active state", async () => {
      const { user } = await openUsers();
      const dialog = await editUser(user, "Max Member");
      await user.selectOptions(dialog.getByLabelText("Role"), "admin");
      await user.type(dialog.getByLabelText("New password"), "NewPassword1");
      const toggle = dialog.getByRole("switch");
      expect(toggle).toHaveAttribute("aria-checked", "true");
      await user.click(toggle);
      expect(toggle).toHaveAttribute("aria-checked", "false");
      expect(toggle).toHaveTextContent("Inactive");
      await user.click(dialog.getByRole("button", { name: "Save" }));
      await waitFor(() => expect(db.userWrites).toHaveLength(1));
      expect(db.userWrites[0].body).toEqual({
        displayName: "Max Member",
        email: "member2@amrap.test",
        password: "NewPassword1",
        role: "admin",
        active: false,
      });
    });

    it("does not let an admin change a member's role or delete them", async () => {
      const { user } = await openUsers("admin");
      const dialog = await editUser(user, "Mia Member");
      expect(dialog.getByLabelText("Role")).toBeDisabled();
      expect(dialog.getByLabelText("Role")).toHaveValue("member");
      expect(dialog.getByRole("switch")).toBeInTheDocument();
      expect(dialog.queryByRole("button", { name: "Delete user" })).toBeNull();
      await user.click(dialog.getByRole("button", { name: "Save" }));
      await waitFor(() => expect(db.userWrites).toHaveLength(1));
      expect(db.userWrites[0].body).not.toHaveProperty("role");
    });

    it("protects superadmin accounts from role, active and delete changes", async () => {
      db.users = [...db.users, { ...db.users[0], id: "u-super2", displayName: "Second Super", email: "super2@amrap.test" }];
      const { user } = await openUsers();
      const dialog = await editUser(user, "Second Super");
      expect(dialog.getByLabelText("Role")).toBeDisabled();
      expect(dialog.queryByRole("switch")).toBeNull();
      expect(dialog.getByText("Active", { selector: ".muted" })).toBeInTheDocument();
      expect(dialog.queryByRole("button", { name: "Delete user" })).toBeNull();
      await user.click(dialog.getByRole("button", { name: "Save" }));
      await waitFor(() => expect(db.userWrites).toHaveLength(1));
      expect(db.userWrites[0].body).not.toHaveProperty("active");
    });

    it("keeps the dialog open and shows the error when saving fails", async () => {
      server.use(http.patch(`${API}/users/:id`, () => HttpResponse.json({ message: "Email already exists" }, { status: 409 })));
      const { user } = await openUsers();
      const dialog = await editUser(user, "Mia Member");
      await user.click(dialog.getByRole("button", { name: "Save" }));
      expect(await screen.findByText("Email already exists")).toBeInTheDocument();
      expect(screen.getByRole("dialog", { name: "Edit user" })).toBeInTheDocument();
    });

    it("closes on Cancel without saving", async () => {
      const { user } = await openUsers();
      const dialog = await editUser(user, "Mia Member");
      await user.click(dialog.getByRole("button", { name: "Cancel" }));
      expect(screen.queryByRole("dialog")).toBeNull();
      expect(db.userWrites).toEqual([]);
    });
  });

  describe("delete", () => {
    it("warns that an admin's members will move to the superadmin, then deletes", async () => {
      const { user } = await openUsers();
      const dialog = await editUser(user, "Alice Admin");
      await user.click(dialog.getByRole("button", { name: "Delete user" }));
      const confirm = within(screen.getByRole("dialog", { name: "Confirm deletion" }));
      expect(confirm.getByText(/has 2/)).toBeInTheDocument();
      expect(confirm.getAllByRole("listitem").map((li) => li.textContent)).toEqual([
        "Mia Member — member@amrap.test",
        "Max Member — member2@amrap.test",
      ]);
      expect(confirm.getByText(/transferred to Superadmin/)).toBeInTheDocument();
      await user.click(confirm.getByRole("button", { name: "Yes, delete" }));
      await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
      expect(db.userWrites).toEqual([{ method: "DELETE", id: "u-admin" }]);
      await waitFor(() => expect(names()).not.toContain("Alice Admin"));
      expect(rowFor("Mia Member").getByRole("cell", { name: "Super Admin" })).toBeInTheDocument();
    });

    it("says when an admin has no members", async () => {
      db.users = [...db.users, { ...db.users[1], id: "u-lonely", displayName: "Lonely Admin", email: "lonely@amrap.test" }];
      const { user } = await openUsers();
      const dialog = await editUser(user, "Lonely Admin");
      await user.click(dialog.getByRole("button", { name: "Delete user" }));
      expect(screen.getByRole("dialog", { name: "Confirm deletion" })).toHaveTextContent("has no members");
    });

    it("names the member being deleted", async () => {
      const { user } = await openUsers();
      const dialog = await editUser(user, "Otto Other");
      await user.click(dialog.getByRole("button", { name: "Delete user" }));
      expect(screen.getByRole("dialog", { name: "Confirm deletion" })).toHaveTextContent(
        "Delete Otto Other (member3@amrap.test)?",
      );
    });

    it("cancels without deleting", async () => {
      const { user } = await openUsers();
      const dialog = await editUser(user, "Otto Other");
      await user.click(dialog.getByRole("button", { name: "Delete user" }));
      await user.click(screen.getByRole("button", { name: "No, cancel" }));
      expect(screen.queryByRole("dialog", { name: "Confirm deletion" })).toBeNull();
      expect(screen.getByRole("dialog", { name: "Edit user" })).toBeInTheDocument();
      expect(db.userWrites).toEqual([]);
    });

    it("shows the error when deleting fails", async () => {
      server.use(http.delete(`${API}/users/:id`, () => HttpResponse.json({ message: "User not found" }, { status: 404 })));
      const { user } = await openUsers();
      const dialog = await editUser(user, "Otto Other");
      await user.click(dialog.getByRole("button", { name: "Delete user" }));
      await user.click(screen.getByRole("button", { name: "Yes, delete" }));
      expect(await screen.findByText("User not found")).toBeInTheDocument();
      expect(names()).toContain("Otto Other");
    });
  });
});
