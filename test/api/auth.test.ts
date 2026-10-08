import { beforeAll, describe, expect, it } from "vitest";
import { ACCOUNTS, TEST_PASSWORD } from "../support/env";
import { api, forgetTokens, tokenFor } from "../support/http";
import { resetAndSeed } from "../support/seed";

beforeAll(async () => {
  await resetAndSeed();
  forgetTokens();
});

function login(body: unknown) {
  return api("/auth/login", { method: "POST", json: body });
}

describe("POST /api/auth/login", () => {
  it("returns a token and the user without any password data", async () => {
    const res = await login({ email: ACCOUNTS.admin.email, password: TEST_PASSWORD });
    expect(res.status).toBe(200);
    expect(res.headers.get("content-type")).toContain("application/json");
    expect(res.body.accessToken).toMatch(/^[\w-]+\.[\w-]+\.[\w-]+$/);
    expect(res.body.user).toMatchObject({
      id: ACCOUNTS.admin.id,
      email: ACCOUNTS.admin.email,
      displayName: ACCOUNTS.admin.name,
      role: "admin",
      createdBy: ACCOUNTS.superadmin.id,
      active: true,
    });
    expect(Object.keys(res.body.user).sort()).toEqual(
      ["active", "createdAt", "createdBy", "displayName", "email", "id", "lastLoginAt", "role", "updatedAt"].sort(),
    );
    expect(res.text.toLowerCase()).not.toContain("password");
  });

  it("matches the email case-insensitively", async () => {
    const res = await login({ email: "MEMBER@Amrap.TEST", password: TEST_PASSWORD });
    expect(res.status).toBe(200);
    expect(res.body.user.role).toBe("member");
  });

  it("records the last login time", async () => {
    await login({ email: ACCOUNTS.member.email, password: TEST_PASSWORD });
    const me = await api("/auth/me", { token: await tokenFor("member") });
    expect(Date.now() - new Date(me.body.lastLoginAt).getTime()).toBeLessThan(60_000);
  });

  it("gives the same 401 for a wrong password and an unknown email", async () => {
    const wrongPassword = await login({ email: ACCOUNTS.admin.email, password: "Wrong-Pass-1" });
    const unknown = await login({ email: "nobody@amrap.test", password: TEST_PASSWORD });
    expect(wrongPassword.status).toBe(401);
    expect(unknown.status).toBe(401);
    expect(wrongPassword.body.message).toBe(unknown.body.message);
    expect(wrongPassword.body).not.toHaveProperty("accessToken");
  });

  it("rejects a deactivated account with 403", async () => {
    const res = await login({ email: ACCOUNTS.inactive.email, password: TEST_PASSWORD });
    expect(res.status).toBe(403);
    expect(res.body).toMatchObject({ status: 403, message: expect.any(String) });
  });

  it.each([
    ["empty object", {}],
    ["missing password", { email: ACCOUNTS.admin.email }],
    ["missing email", { password: TEST_PASSWORD }],
    ["blank email", { email: "  ", password: TEST_PASSWORD }],
    ["blank password", { email: ACCOUNTS.admin.email, password: "" }],
    ["malformed email", { email: "not-an-email", password: TEST_PASSWORD }],
    ["null values", { email: null, password: null }],
    ["array body", [ACCOUNTS.admin.email, TEST_PASSWORD]],
  ])("rejects %s with 400", async (_name, body) => {
    const res = await login(body);
    expect(res.status).toBe(400);
    expect(res.body).toMatchObject({ status: 400, message: expect.any(String), timestamp: expect.any(String) });
  });

  it("rejects malformed JSON with 400", async () => {
    const res = await api("/auth/login", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: '{"email": "a@b.io", "password": ',
    });
    expect(res.status).toBe(400);
  });

  it("rejects a non-JSON content type with 415", async () => {
    const res = await api("/auth/login", {
      method: "POST",
      headers: { "Content-Type": "text/plain" },
      body: "email=a@b.io",
    });
    expect(res.status).toBe(415);
  });
});

describe("GET /api/auth/me", () => {
  it("returns the current user for a valid token", async () => {
    const res = await api("/auth/me", { token: await tokenFor("superadmin") });
    expect(res.status).toBe(200);
    expect(res.body).toMatchObject({ id: ACCOUNTS.superadmin.id, role: "superadmin", createdBy: null });
  });

  it.each([
    ["no token", undefined],
    ["a garbage token", "garbage"],
    ["a token with a forged signature", "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4In0.c2lnbmF0dXJl"],
  ])("returns a JSON 401 for %s", async (_name, token) => {
    const res = await api("/auth/me", { token });
    expect(res.status).toBe(401);
    expect(res.body).toMatchObject({ status: 401, message: "Authentication required" });
  });

  it("rejects a non-Bearer authorization scheme", async () => {
    const res = await api("/auth/me", { headers: { Authorization: `Basic ${btoa("super@amrap.test:x")}` } });
    expect(res.status).toBe(401);
  });
});
