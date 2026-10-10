import { beforeAll, describe, expect, it } from "vitest";
import { API_ORIGIN, WEB_URL } from "../support/env";
import { api, forgetTokens, tokenFor } from "../support/http";
import { resetAndSeed } from "../support/seed";

beforeAll(async () => {
  await resetAndSeed();
  forgetTokens();
});

describe("platform", () => {
  it("exposes a public health check", async () => {
    const res = await api(`${API_ORIGIN}/actuator/health`);
    expect(res.status).toBe(200);
    expect(res.body.status).toBe("UP");
  });

  it("does not expose other actuator endpoints", async () => {
    const token = await tokenFor("superadmin");
    for (const path of ["/actuator/env", "/actuator/beans", "/actuator/configprops"]) {
      const res = await api(`${API_ORIGIN}${path}`, { token });
      expect(res.status).not.toBe(200);
    }
  });

  it("answers a CORS preflight from another origin", async () => {
    const res = await fetch(`${API_ORIGIN}/api/results`, {
      method: "OPTIONS",
      headers: {
        Origin: "http://lan-client.example:5173",
        "Access-Control-Request-Method": "GET",
        "Access-Control-Request-Headers": "authorization,content-type",
      },
    });
    expect(res.status).toBe(200);
    expect(res.headers.get("access-control-allow-origin")).toBe("http://lan-client.example:5173");
    expect(res.headers.get("access-control-allow-methods")).toContain("GET");
    expect(res.headers.get("access-control-allow-headers")?.toLowerCase()).toContain("authorization");
  });

  it("returns JSON errors for unknown routes and wrong methods", async () => {
    const token = await tokenFor("member");
    const missing = await api("/does-not-exist", { token });
    expect(missing.status).toBe(404);
    expect(missing.body).toMatchObject({ status: 404 });
    const wrongMethod = await api("/auth/login", { method: "GET" });
    expect([401, 405]).toContain(wrongMethod.status);
    const put = await api("/results", { method: "PUT", token, json: {} });
    expect(put.status).toBe(405);
  });

  it("never leaks stack traces in error bodies", async () => {
    const token = await tokenFor("member");
    const res = await api("/results?page=abc&pageSize=xyz", { token });
    expect(res.status).toBe(400);
    expect(res.text).not.toMatch(/Exception|at com\.amrap|org\.springframework/);
  });
});

describe("web gateway", () => {
  it("serves the single-page app for client routes", async () => {
    for (const path of ["/", "/results", "/users", "/chat", "/login"]) {
      const res = await fetch(`${WEB_URL}${path}`);
      expect(res.status).toBe(200);
      expect(await res.text()).toContain('<div id="root">');
    }
  });

  it("proxies /api to the backend with the same responses", async () => {
    const token = await tokenFor("member");
    const direct = await api("/results?model=CardioNet&pageSize=5", { token });
    const proxied = await api(`${WEB_URL}/api/results?model=CardioNet&pageSize=5`, { token });
    expect(proxied.status).toBe(200);
    expect(proxied.body).toEqual(direct.body);
    expect((await api(`${WEB_URL}/api/results`)).status).toBe(401);
  });
});

describe("POST /api/chat without a language model", () => {
  it("fails with 502 and a JSON error instead of hanging or crashing", async () => {
    const started = Date.now();
    const res = await api("/chat", { method: "POST", token: await tokenFor("member"), json: { question: "Which model is best?" } });
    expect(res.status).toBe(502);
    expect(res.body).toMatchObject({ status: 502, message: expect.stringMatching(/failed/i) });
    expect(Date.now() - started).toBeLessThan(25_000);
  });

  it.each([
    ["empty question", { question: "" }],
    ["blank question", { question: "   " }],
    ["missing question", {}],
    ["invalid date", { question: "q", fromIso: "yesterday-ish" }],
  ])("rejects an %s with 400", async (_name, body) => {
    const res = await api("/chat", { method: "POST", token: await tokenFor("member"), json: body });
    expect(res.status).toBe(400);
  });

  it("requires authentication", async () => {
    expect((await api("/chat", { method: "POST", json: { question: "q" } })).status).toBe(401);
  });
});
