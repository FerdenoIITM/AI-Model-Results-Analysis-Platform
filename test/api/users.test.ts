import { beforeAll, describe, expect, it } from "vitest";
import { ACCOUNTS, TEST_PASSWORD } from "../support/env";
import { api, forgetTokens, tokenFor } from "../support/http";
import { resetAndSeed, sql } from "../support/seed";

beforeAll(async () => {
  await resetAndSeed();
  forgetTokens();
});

let seq = 0;
function newUser(role: "admin" | "member" = "member", overrides: Record<string, unknown> = {}) {
  seq += 1;
  return { email: `user${seq}.${Date.now()}@amrap.test`, password: "Password123!", displayName: `User ${seq}`, role, ...overrides };
}

async function create(as: "superadmin" | "admin" | "member", body: unknown) {
  return api("/users", { method: "POST", token: await tokenFor(as), json: body });
}

describe("GET /api/users", () => {
  it("shows the superadmin every other account, but not their own row", async () => {
    const res = await api("/users", { token: await tokenFor("superadmin") });
    expect(res.status).toBe(200);
    const emails = res.body.map((u: any) => u.email);
    for (const role of ["admin", "member", "inactive"] as const) expect(emails).toContain(ACCOUNTS[role].email);
    expect(emails).not.toContain(ACCOUNTS.superadmin.email);
    expect(res.text).not.toMatch(/password/i);
  });

  it("shows an admin only the members they created", async () => {
    const res = await api("/users", { token: await tokenFor("admin") });
    expect(res.status).toBe(200);
    expect(res.body.length).toBeGreaterThan(0);
    for (const u of res.body) expect(u.createdBy).toBe(ACCOUNTS.admin.id);
    expect(res.body.map((u: any) => u.email)).not.toContain(ACCOUNTS.admin.email);
  });

  it("forbids members", async () => {
    expect((await api("/users", { token: await tokenFor("member") })).status).toBe(403);
  });

  it("requires authentication", async () => {
    expect((await api("/users")).status).toBe(401);
  });
});

describe("POST /api/users", () => {
  it("lets an admin create a member owned by them", async () => {
    const body = newUser("member");
    const res = await create("admin", body);
    expect(res.status).toBe(201);
    expect(res.body).toMatchObject({ email: body.email, role: "member", createdBy: ACCOUNTS.admin.id, active: true });
    const login = await api("/auth/login", { method: "POST", json: { email: body.email, password: body.password } });
    expect(login.status).toBe(200);
  });

  it("lowercases the stored email", async () => {
    const body = newUser("member", { email: `Mixed.Case${Date.now()}@AMRAP.test` });
    const res = await create("superadmin", body);
    expect(res.status).toBe(201);
    expect(res.body.email).toBe((body.email as string).toLowerCase());
  });

  it("lets the superadmin create an admin", async () => {
    expect((await create("superadmin", newUser("admin"))).status).toBe(201);
  });

  it.each([
    ["admin", "admin"],
    ["admin", "superadmin"],
    ["superadmin", "superadmin"],
  ] as const)("forbids %s from creating a %s", async (as, role) => {
    const res = await create(as, newUser("member", { role }));
    expect(res.status).toBe(403);
  });

  it("forbids members from creating anyone", async () => {
    expect((await create("member", newUser("member"))).status).toBe(403);
  });

  it("rejects a duplicate email regardless of case with 409", async () => {
    const res = await create("superadmin", newUser("member", { email: ACCOUNTS.member.email.toUpperCase() }));
    expect(res.status).toBe(409);
    expect(res.body.message).toEqual(expect.any(String));
  });

  it("allows only one of several simultaneous creates with the same email", async () => {
    const body = newUser("member");
    const token = await tokenFor("superadmin");
    const results = await Promise.all(
      Array.from({ length: 6 }, () => api("/users", { method: "POST", token, json: body })),
    );
    const statuses = results.map((r) => r.status).sort();
    expect(statuses).toEqual([201, 409, 409, 409, 409, 409]);
    const rows = await sql("SELECT id FROM users WHERE email = $1", [body.email]);
    expect(rows).toHaveLength(1);
  });

  it.each([
    ["missing email", { email: undefined }],
    ["malformed email", { email: "nope" }],
    ["password under 8 characters", { password: "Short1!" }],
    ["password over 128 characters", { password: "p".repeat(129) }],
    ["blank display name", { displayName: " " }],
    ["display name over 120 characters", { displayName: "d".repeat(121) }],
    ["missing role", { role: undefined }],
    ["unknown role", { role: "root" }],
    ["role with wrong type", { role: 1 }],
  ])("rejects %s with 400", async (_name, overrides) => {
    const res = await create("superadmin", newUser("member", overrides));
    expect(res.status).toBe(400);
    expect(res.body.status).toBe(400);
  });

  it("accepts the boundary lengths", async () => {
    const res = await create("superadmin", newUser("member", { password: "p".repeat(128), displayName: "d".repeat(120) }));
    expect(res.status).toBe(201);
  });
});

describe("PATCH / deactivate / DELETE /api/users/:id", () => {
  it("updates a member's display name and keeps other fields", async () => {
    const created = (await create("admin", newUser("member"))).body;
    const res = await api(`/users/${created.id}`, {
      method: "PATCH",
      token: await tokenFor("admin"),
      json: { displayName: "Renamed Member" },
    });
    expect(res.status).toBe(200);
    expect(res.body).toMatchObject({ id: created.id, displayName: "Renamed Member", email: created.email, role: "member" });
    expect(new Date(res.body.updatedAt).getTime()).toBeGreaterThanOrEqual(new Date(created.updatedAt).getTime());
  });

  it("forbids an admin from editing someone else's member", async () => {
    const other = (await create("superadmin", newUser("member"))).body;
    const res = await api(`/users/${other.id}`, { method: "PATCH", token: await tokenFor("admin"), json: { displayName: "x" } });
    expect(res.status).toBe(403);
  });

  it("blocks login after deactivation", async () => {
    const body = newUser("member");
    const created = (await create("admin", body)).body;
    const res = await api(`/users/${created.id}/deactivate`, { method: "POST", token: await tokenFor("admin") });
    expect(res.status).toBe(200);
    expect(res.body.active).toBe(false);
    const login = await api("/auth/login", { method: "POST", json: { email: body.email, password: body.password } });
    expect(login.status).toBe(403);
  });

  it("rejects invalid update values", async () => {
    const created = (await create("superadmin", newUser("member"))).body;
    const token = await tokenFor("superadmin");
    for (const json of [{ email: "bad" }, { password: "short" }, { displayName: "x".repeat(121) }]) {
      const res = await api(`/users/${created.id}`, { method: "PATCH", token, json });
      expect(res.status).toBe(400);
    }
  });

  it("lets the superadmin delete a member", async () => {
    const created = (await create("superadmin", newUser("member"))).body;
    const res = await api(`/users/${created.id}`, { method: "DELETE", token: await tokenFor("superadmin") });
    expect(res.status).toBe(204);
    expect(await sql("SELECT 1 FROM users WHERE id = $1", [created.id])).toHaveLength(0);
  });

  it("moves an admin's members to the superadmin when the admin is deleted", async () => {
    const admin = (await create("superadmin", newUser("admin"))).body;
    const adminLogin = await api("/auth/login", {
      method: "POST",
      json: { email: admin.email, password: "Password123!" },
    });
    const member = (
      await api("/users", { method: "POST", token: adminLogin.body.accessToken, json: newUser("member") })
    ).body;
    expect(member.createdBy).toBe(admin.id);
    const res = await api(`/users/${admin.id}`, { method: "DELETE", token: await tokenFor("superadmin") });
    expect(res.status).toBe(204);
    const [row] = await sql<{ created_by: string }>("SELECT created_by FROM users WHERE id = $1", [member.id]);
    expect(row.created_by).toBe(ACCOUNTS.superadmin.id);
  });

  it("forbids admins from deleting and protects the superadmin", async () => {
    const created = (await create("admin", newUser("member"))).body;
    expect((await api(`/users/${created.id}`, { method: "DELETE", token: await tokenFor("admin") })).status).toBe(403);
    const self = await api(`/users/${ACCOUNTS.superadmin.id}`, { method: "DELETE", token: await tokenFor("superadmin") });
    expect([400, 403]).toContain(self.status);
  });

  it("returns 404 for an unknown user and 400 for a malformed id", async () => {
    const token = await tokenFor("superadmin");
    expect((await api("/users/11111111-1111-4111-8111-111111111111", { method: "DELETE", token })).status).toBe(404);
    expect((await api("/users/not-a-uuid", { method: "DELETE", token })).status).toBe(400);
    expect((await api("/users/not-a-uuid", { method: "PATCH", token, json: { displayName: "x" } })).status).toBe(400);
  });

  it("keeps working with the seeded password after all of this", async () => {
    const res = await api("/auth/login", { method: "POST", json: { email: ACCOUNTS.superadmin.email, password: TEST_PASSWORD } });
    expect(res.status).toBe(200);
  });
});
