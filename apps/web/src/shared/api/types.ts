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
