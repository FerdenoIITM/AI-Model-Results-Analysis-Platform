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
  category: string | null;
  subCategory: string | null;
  confidenceAccuracy: number | null;
  sensitivity: number | null;
  specificity: number | null;
  f1Score: number | null;
  recall: number | null;
  precision: number | null;
  jsonPath: string;
  sizeBytes: number;
  insertedAt: string;
  vectorized: boolean;
  vectorizedAt: string | null;
  createdBy: string | null;
}

export interface ResultPage {
  results: ResultSummary[];
  total: number;
  page: number;
  pageSize: number;
}

export interface FilterOptions {
  models: string[];
  categories: string[];
  subCategories: string[];
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
  modelNames?: string[];
  categories?: string[];
  subCategories?: string[];
  history?: ChatTurn[];
}

export interface CreateResultRequest {
  modelName: string;
  version: string;
  category?: string | null;
  subCategory?: string | null;
  confidenceAccuracy?: number | null;
  sensitivity?: number | null;
  specificity?: number | null;
  f1Score?: number | null;
  recall?: number | null;
  precision?: number | null;
  payload: unknown;
}

export interface ReindexResponse {
  reset: number;
  message: string;
}
