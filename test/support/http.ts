import { ACCOUNTS, API_URL, TEST_PASSWORD, type AccountRole } from "./env";

export interface ApiResponse<T = any> {
  status: number;
  headers: Headers;
  body: T;
  text: string;
}

export async function api<T = any>(
  path: string,
  init: RequestInit & { token?: string; json?: unknown } = {},
): Promise<ApiResponse<T>> {
  const headers = new Headers(init.headers);
  if (init.token) headers.set("Authorization", `Bearer ${init.token}`);
  let body = init.body;
  if (init.json !== undefined) {
    headers.set("Content-Type", "application/json");
    body = JSON.stringify(init.json);
  }
  const res = await fetch(path.startsWith("http") ? path : `${API_URL}${path}`, { ...init, headers, body });
  const text = await res.text();
  let parsed: unknown = text;
  try {
    parsed = text ? JSON.parse(text) : null;
  } catch {
    // non-JSON body; keep the text
  }
  return { status: res.status, headers: res.headers, body: parsed as T, text };
}

const tokens = new Map<AccountRole, string>();

export async function tokenFor(role: AccountRole): Promise<string> {
  const cached = tokens.get(role);
  if (cached) return cached;
  const res = await api("/auth/login", {
    method: "POST",
    json: { email: ACCOUNTS[role].email, password: TEST_PASSWORD },
  });
  if (res.status !== 200) throw new Error(`Login as ${role} failed: ${res.status} ${res.text}`);
  tokens.set(role, res.body.accessToken);
  return res.body.accessToken;
}

/** Tokens are bound to user ids, which survive a reset because seed ids are fixed. */
export function forgetTokens() {
  tokens.clear();
}
