export type UserRole = "superadmin" | "admin" | "member";

export interface UserSummary {
  id: string;
  email: string;
  displayName: string;
  role: UserRole;
  createdBy: string | null;
  active: boolean;
  lastLoginAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface LoginResponse {
  accessToken: string;
  user: UserSummary;
}

export interface ResultSummary {
  id: string;
  modelName: string;
  version: string;
  iterationId: string;
  jsonPath: string;
  sizeBytes: number;
  insertedAt: string;
  vectorized: boolean;
  vectorizedAt: string | null;
  createdBy: string | null;
}

export interface ChatCitation {
  resultId: string | null;
  modelName: string | null;
  version: string | null;
  iterationId: string | null;
  insertedAt: string | null;
  chunkIndex: number | null;
  score: number | null;
}

export interface ChatResponse {
  answer: string;
  citations: ChatCitation[];
}

export interface ChatTurn {
  role: "user" | "assistant";
  text: string;
}

export interface ChatRequest {
  question: string;
  modelName?: string;
  version?: string;
  iterationId?: string;
  fromIso?: string;
  toIso?: string;
  history?: ChatTurn[];
}

export interface CreateResultRequest {
  modelName: string;
  version: string;
  iterationId: string;
  payload: unknown;
}

export interface ReindexResponse {
  reset: number;
  message: string;
}
