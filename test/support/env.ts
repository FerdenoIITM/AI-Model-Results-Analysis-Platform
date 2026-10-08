/** Endpoints of the isolated test stack, as seen from a container on its network. */
export const API_URL = process.env.API_URL ?? "http://api:8080/api";
export const API_ORIGIN = API_URL.replace(/\/api$/, "");
export const WEB_URL = process.env.WEB_URL ?? "http://gateway";
export const DB_URL = process.env.DB_URL ?? "postgres://amrap:amrap-test-only@db:5432/amrap";
export const INGEST_KEY = process.env.INGEST_KEY ?? "amrap-test-ingest-key";

/** Password of every account in test/fixtures/seed.sql. */
export const TEST_PASSWORD = "Test-Pass-123!";

export const ACCOUNTS = {
  superadmin: { id: "00000000-0000-4000-8000-000000000001", email: "super@amrap.test", name: "Test Superadmin" },
  admin: { id: "00000000-0000-4000-8000-000000000002", email: "admin@amrap.test", name: "Test Admin" },
  member: { id: "00000000-0000-4000-8000-000000000003", email: "member@amrap.test", name: "Test Member" },
  inactive: { id: "00000000-0000-4000-8000-000000000004", email: "inactive@amrap.test", name: "Inactive Member" },
} as const;

export type AccountRole = keyof typeof ACCOUNTS;
