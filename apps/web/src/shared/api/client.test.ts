import { http, HttpResponse } from "msw";
import { describe, expect, it, vi } from "vitest";
import { API, server, tokenFor } from "../../test/server";
import {
  createResult,
  createUser,
  deactivateUser,
  deleteUser,
  fetchFilterOptions,
  fetchMe,
  fetchResultPayload,
  listResults,
  listUsers,
  login,
  reindexResults,
  sendChat,
  updateUser,
} from "./client";

const SUPER = tokenFor("u-super");

function capture(method: "get" | "post" | "patch" | "delete", path: string, response: () => Response) {
  const seen: Request[] = [];
  server.use(
    http[method](`${API}${path}`, ({ request }) => {
      seen.push(request.clone());
      return response();
    }),
  );
  return seen;
}

describe("api client", () => {
  describe("requests", () => {
    it("sends JSON content type and no Authorization header when logging in", async () => {
      const seen = capture("post", "/auth/login", () => HttpResponse.json({ accessToken: "t", user: {} }));
      await login("a@b.io", "pw");
      expect(seen[0].headers.get("Content-Type")).toBe("application/json");
      expect(seen[0].headers.get("Authorization")).toBeNull();
      expect(await seen[0].json()).toEqual({ email: "a@b.io", password: "pw" });
    });

    it("sends the bearer token on authenticated calls", async () => {
      const seen = capture("get", "/auth/me", () => HttpResponse.json({ id: "u" }));
      await fetchMe("abc.def");
      expect(seen[0].headers.get("Authorization")).toBe("Bearer abc.def");
    });

    it("passes result query params through unchanged, including repeated keys", async () => {
      const seen = capture("get", "/results", () => HttpResponse.json({ results: [], total: 0, page: 1, pageSize: 50 }));
      const params = new URLSearchParams("model=A&model=B%20C&confidenceAccuracyGte=95&sort=-recall");
      await listResults(SUPER, params);
      const url = new URL(seen[0].url);
      expect(url.searchParams.getAll("model")).toEqual(["A", "B C"]);
      expect(url.searchParams.get("confidenceAccuracyGte")).toBe("95");
      expect(url.searchParams.get("sort")).toBe("-recall");
    });

    it("omits the query string when there are no params", async () => {
      const seen = capture("get", "/results", () => HttpResponse.json({ results: [], total: 0, page: 1, pageSize: 50 }));
      await listResults(SUPER, new URLSearchParams());
      expect(seen[0].url).toBe(`${API}/results`);
    });

    it("sends one category param per selected category for filter options", async () => {
      const seen = capture("get", "/results/filter-options", () =>
        HttpResponse.json({ models: [], categories: [], subCategories: [] }),
      );
      await fetchFilterOptions(SUPER, ["Cardiology", "Neuro & Spine"]);
      await fetchFilterOptions(SUPER, []);
      expect(new URL(seen[0].url).searchParams.getAll("category")).toEqual(["Cardiology", "Neuro & Spine"]);
      expect(seen[1].url).toBe(`${API}/results/filter-options`);
    });

    it("uses the right method and path for every write call", async () => {
      const calls: string[] = [];
      server.use(
        http.all(`${API}/*`, ({ request }) => {
          calls.push(`${request.method} ${new URL(request.url).pathname}`);
          return request.method === "DELETE" ? new HttpResponse(null, { status: 204 }) : HttpResponse.json({});
        }),
      );
      await createUser(SUPER, { email: "e@x.io", password: "Password1", displayName: "E", role: "member" });
      await updateUser(SUPER, "u1", { displayName: "N" });
      await deactivateUser(SUPER, "u1");
      await deleteUser(SUPER, "u1");
      await createResult(SUPER, { modelName: "M", version: "1", payload: {} });
      await reindexResults(SUPER);
      await sendChat(SUPER, { question: "q" });
      expect(calls).toEqual([
        "POST /api/users",
        "PATCH /api/users/u1",
        "POST /api/users/u1/deactivate",
        "DELETE /api/users/u1",
        "POST /api/results",
        "POST /api/results/reindex",
        "POST /api/chat",
      ]);
    });

    it("returns the raw payload text without parsing it", async () => {
      capture("get", "/results/r1/payload", () => new HttpResponse("not { json", { status: 200 }));
      await expect(fetchResultPayload(SUPER, "r1")).resolves.toBe("not { json");
    });
  });

  describe("errors", () => {
    it("uses the server's message field", async () => {
      capture("get", "/users", () => HttpResponse.json({ status: 403, message: "Forbidden for members" }, { status: 403 }));
      await expect(listUsers(SUPER)).rejects.toThrow("Forbidden for members");
    });

    it("falls back to the status text when the body has no message", async () => {
      capture("get", "/users", () => HttpResponse.json({ error: "x" }, { status: 409, statusText: "Conflict" }));
      await expect(listUsers(SUPER)).rejects.toThrow("Conflict");
    });

    it("falls back to the status text when the body is not JSON", async () => {
      capture("get", "/results", () => new HttpResponse("<html>bad gateway</html>", { status: 502, statusText: "Bad Gateway" }));
      await expect(listResults(SUPER, new URLSearchParams())).rejects.toThrow("Bad Gateway");
    });

    it("uses a generic message when there is neither a body nor a status text", async () => {
      vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(null, { status: 500, statusText: "" }));
      await expect(sendChat(SUPER, { question: "q" })).rejects.toThrow("Request failed");
    });

    it("propagates network failures", async () => {
      server.use(http.get(`${API}/auth/me`, () => HttpResponse.error()));
      await expect(fetchMe(SUPER)).rejects.toThrow();
    });

    it("rejects with AbortError when the signal is aborted", async () => {
      server.use(
        http.get(`${API}/results`, async () => {
          await new Promise((resolve) => setTimeout(resolve, 200));
          return HttpResponse.json({ results: [], total: 0, page: 1, pageSize: 50 });
        }),
      );
      const controller = new AbortController();
      const pending = listResults(SUPER, new URLSearchParams(), controller.signal);
      controller.abort();
      await expect(pending).rejects.toMatchObject({ name: "AbortError" });
    });

    it.each([
      ["deleteUser", () => deleteUser(SUPER, "missing"), "delete", "/users/missing"],
      ["reindexResults", () => reindexResults(SUPER), "post", "/results/reindex"],
      ["fetchResultPayload", () => fetchResultPayload(SUPER, "gone"), "get", "/results/gone/payload"],
      ["createResult", () => createResult(SUPER, { modelName: "", version: "", payload: null }), "post", "/results"],
    ] as const)("%s throws the server message on failure", async (_name, call, method, path) => {
      capture(method, path, () => HttpResponse.json({ message: `boom ${path}` }, { status: 400 }));
      await expect(call()).rejects.toThrow(`boom ${path}`);
    });
  });
});
