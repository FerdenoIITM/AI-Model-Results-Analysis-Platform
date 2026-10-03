import type {
  ChatRequest,
  ChatResponse,
  CreateResultRequest,
  LoginResponse,
  ReindexResponse,
  ResultSummary,
  UserRole,
  UserSummary,
} from "./types";

const API_BASE = import.meta.env.VITE_API_BASE_URL ?? "/api";

function authHeaders(token?: string | null): HeadersInit {
  const headers: Record<string, string> = {
    "Content-Type": "application/json",
  };
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }
  return headers;
}

async function parseError(res: Response): Promise<string> {
  try {
    const body = await res.json();
    return body.message ?? res.statusText;
  } catch {
    return res.statusText || "Request failed";
  }
}

export async function login(email: string, password: string): Promise<LoginResponse> {
  const res = await fetch(`${API_BASE}/auth/login`, {
    method: "POST",
    headers: authHeaders(),
    body: JSON.stringify({ email, password }),
  });
  if (!res.ok) {
    throw new Error(await parseError(res));
  }
  return res.json();
}

export async function fetchMe(token: string): Promise<UserSummary> {
  const res = await fetch(`${API_BASE}/auth/me`, {
    headers: authHeaders(token),
  });
  if (!res.ok) {
    throw new Error(await parseError(res));
  }
  return res.json();
}

export async function listUsers(token: string): Promise<UserSummary[]> {
  const res = await fetch(`${API_BASE}/users`, {
    headers: authHeaders(token),
  });
  if (!res.ok) {
    throw new Error(await parseError(res));
  }
  return res.json();
}

export async function createUser(
  token: string,
  payload: { email: string; password: string; displayName: string; role: UserRole }
): Promise<UserSummary> {
  const res = await fetch(`${API_BASE}/users`, {
    method: "POST",
    headers: authHeaders(token),
    body: JSON.stringify(payload),
  });
  if (!res.ok) {
    throw new Error(await parseError(res));
  }
  return res.json();
}

export async function updateUser(
  token: string,
  id: string,
  payload: {
    displayName?: string;
    email?: string;
    password?: string;
    role?: UserRole;
    active?: boolean;
  }
): Promise<UserSummary> {
  const res = await fetch(`${API_BASE}/users/${id}`, {
    method: "PATCH",
    headers: authHeaders(token),
    body: JSON.stringify(payload),
  });
  if (!res.ok) {
    throw new Error(await parseError(res));
  }
  return res.json();
}

export async function deactivateUser(token: string, id: string): Promise<UserSummary> {
  const res = await fetch(`${API_BASE}/users/${id}/deactivate`, {
    method: "POST",
    headers: authHeaders(token),
  });
  if (!res.ok) {
    throw new Error(await parseError(res));
  }
  return res.json();
}

export async function deleteUser(token: string, id: string): Promise<void> {
  const res = await fetch(`${API_BASE}/users/${id}`, {
    method: "DELETE",
    headers: authHeaders(token),
  });
  if (!res.ok) {
    throw new Error(await parseError(res));
  }
}

// --- Results (RAG) ---

export async function listResults(token: string, limit = 100): Promise<ResultSummary[]> {
  const res = await fetch(`${API_BASE}/results?limit=${limit}`, {
    headers: authHeaders(token),
  });
  if (!res.ok) {
    throw new Error(await parseError(res));
  }
  return res.json();
}

export async function createResult(
  token: string,
  payload: CreateResultRequest
): Promise<ResultSummary> {
  const res = await fetch(`${API_BASE}/results`, {
    method: "POST",
    headers: authHeaders(token),
    body: JSON.stringify(payload),
  });
  if (!res.ok) {
    throw new Error(await parseError(res));
  }
  return res.json();
}

export async function fetchResultPayload(token: string, id: string): Promise<string> {
  const res = await fetch(`${API_BASE}/results/${id}/payload`, {
    headers: authHeaders(token),
  });
  if (!res.ok) {
    throw new Error(await parseError(res));
  }
  return res.text();
}

export async function reindexResults(token: string): Promise<ReindexResponse> {
  const res = await fetch(`${API_BASE}/results/reindex`, {
    method: "POST",
    headers: authHeaders(token),
  });
  if (!res.ok) {
    throw new Error(await parseError(res));
  }
  return res.json();
}

export async function sendChat(token: string, request: ChatRequest): Promise<ChatResponse> {
  const res = await fetch(`${API_BASE}/chat`, {
    method: "POST",
    headers: authHeaders(token),
    body: JSON.stringify(request),
  });
  if (!res.ok) {
    throw new Error(await parseError(res));
  }
  return res.json();
}
