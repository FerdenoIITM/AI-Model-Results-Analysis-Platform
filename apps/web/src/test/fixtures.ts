import type { ResultSummary, UserRole, UserSummary } from "../shared/api/types";

export const PASSWORD = "Password123!";

const CREATED = "2026-01-15T09:30:00Z";

function user(id: string, role: UserRole, displayName: string, email: string, createdBy: string | null): UserSummary {
  return {
    id,
    email,
    displayName,
    role,
    createdBy,
    active: true,
    lastLoginAt: null,
    createdAt: CREATED,
    updatedAt: CREATED,
  };
}

export function seedUsers(): UserSummary[] {
  return [
    user("u-super", "superadmin", "Super Admin", "super@amrap.test", null),
    user("u-admin", "admin", "Alice Admin", "admin@amrap.test", "u-super"),
    user("u-admin2", "admin", "Bob Admin", "admin2@amrap.test", "u-super"),
    user("u-member", "member", "Mia Member", "member@amrap.test", "u-admin"),
    user("u-member2", "member", "Max Member", "member2@amrap.test", "u-admin"),
    user("u-member3", "member", "Otto Other", "member3@amrap.test", "u-admin2"),
  ];
}

export function userId(role: UserRole): string {
  return { superadmin: "u-super", admin: "u-admin", member: "u-member" }[role];
}

let counter = 0;

export function makeResult(overrides: Partial<ResultSummary> = {}): ResultSummary {
  counter += 1;
  const n = counter;
  return {
    id: `r-${String(n).padStart(4, "0")}`,
    modelName: "CardioNet",
    version: "1.0.0",
    iterationId: String(20260101000000 + n),
    category: "Cardiology",
    subCategory: "Aortic Valve",
    confidenceAccuracy: 90,
    sensitivity: 90,
    specificity: 90,
    f1Score: 90,
    recall: 90,
    precision: 90,
    jsonPath: `/data/results/r-${n}.json`,
    sizeBytes: 2048,
    insertedAt: new Date(Date.UTC(2026, 0, 1, 0, 0) + n * 60_000).toISOString(),
    vectorized: true,
    vectorizedAt: null,
    createdBy: "u-super",
    ...overrides,
  };
}

/** Small mixed dataset: several models, categories and sub categories, boundary values and nulls. */
export function seedResults(): ResultSummary[] {
  counter = 0;
  return [
    makeResult({ modelName: "CardioNet", version: "1.0.0", category: "Cardiology", subCategory: "Aortic Valve", confidenceAccuracy: 97.5, sensitivity: 92, recall: 91.25 }),
    makeResult({ modelName: "CardioNet", version: "2.0.0", category: "Cardiology", subCategory: "Arrhythmia", confidenceAccuracy: 94.99, sensitivity: 96 }),
    makeResult({ modelName: "NeuroSlice", version: "1.0.0", category: "Neurology", subCategory: "Stroke", confidenceAccuracy: 99.1, sensitivity: 98 }),
    makeResult({ modelName: "NeuroSlice", version: "1.1.0", category: "Neurology", subCategory: "Tumor", confidenceAccuracy: 88, sensitivity: 85, vectorized: false }),
    makeResult({ modelName: "ChestScan", version: "3.0.0", category: "Pulmonology", subCategory: "Nodule", confidenceAccuracy: 95, sensitivity: 9.5 }),
    makeResult({ modelName: "ChestScan", version: "3.0.0", category: "Pulmonology", subCategory: "Pneumonia", confidenceAccuracy: null, sensitivity: null, specificity: null, f1Score: null, recall: null, precision: null }),
    makeResult({ modelName: "CardioNet", version: "3.0.0", category: "Cardiology", subCategory: "Aortic Valve", confidenceAccuracy: 100, sensitivity: 100, sizeBytes: 3 * 1024 * 1024 }),
    makeResult({ modelName: "LegacyModel", version: "0.1.0", category: null, subCategory: null, confidenceAccuracy: 10, sensitivity: 0, vectorized: false, sizeBytes: 512 }),
  ];
}

/** Large dataset for pagination and volume checks. */
export function largeResults(count: number): ResultSummary[] {
  counter = 0;
  const models = ["CardioNet", "NeuroSlice", "ChestScan"];
  const categories = ["Cardiology", "Neurology", "Pulmonology"];
  return Array.from({ length: count }, (_, i) =>
    makeResult({
      modelName: models[i % 3],
      category: categories[i % 3],
      subCategory: `Sub ${i % 5}`,
      confidenceAccuracy: (i * 7) % 101,
    }),
  );
}
