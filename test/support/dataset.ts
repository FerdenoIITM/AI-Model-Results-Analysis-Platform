export interface SeedResult {
  modelName: string;
  version: string;
  category: string | null;
  subCategory: string | null;
  confidenceAccuracy: number | null;
  sensitivity: number | null;
  specificity: number | null;
  f1Score: number | null;
  recall: number | null;
  precision: number | null;
  payload: unknown;
}

function row(
  modelName: string,
  version: string,
  category: string | null,
  subCategory: string | null,
  confidenceAccuracy: number | null,
  sensitivity: number | null = 90,
): SeedResult {
  return {
    modelName,
    version,
    category,
    subCategory,
    confidenceAccuracy,
    sensitivity,
    specificity: 90,
    f1Score: 90,
    recall: 90,
    precision: 90,
    payload: { modelName, version, notes: `seed ${modelName} ${version}` },
  };
}

/**
 * Hand-picked rows for the filter flow. CardioNet + Cardiology + confidence >= 95 matches exactly
 * 100, 99.5, 97 and 95; 100 vs 95 and 9.5 vs 94.99 would sort wrongly as strings.
 */
export const FLOW_ROWS: SeedResult[] = [
  row("CardioNet", "1.3.0", "Cardiology", "Arrhythmia", 100, 99),
  row("CardioNet", "1.0.0", "Cardiology", "Aortic Valve", 99.5, 92),
  row("CardioNet", "1.1.0", "Cardiology", "Aortic Valve", 97, 9.5),
  row("CardioNet", "1.2.0", "Cardiology", "Arrhythmia", 95, 100),
  row("CardioNet", "0.9.0", "Cardiology", "Aortic Valve", 94.99, 0),
  row("CardioNet", "0.1.0", "Cardiology", "Arrhythmia", 9.5, null),
  row("CardioNet", "0.0.1", "Cardiology", "Aortic Valve", null, 50),
  row("CardioNet", "2.0.0", "Neurology", "Stroke", 98),
  row("NeuroSlice", "1.0.0", "Neurology", "Stroke", 99),
  row("NeuroSlice", "1.1.0", "Neurology", "Tumor", 90),
  row("NeuroSlice", "0.5.0", "Cardiology", "Aortic Valve", 96),
  row("ChestScan", "3.0.0", "Pulmonology", "Nodule", 85),
  row("ChestScan", "3.1.0", "Pulmonology", "Pneumonia", 92),
];

/** Bulk rows for pagination and volume checks. */
export const BULK_ROWS: SeedResult[] = Array.from({ length: 120 }, (_, i) =>
  row("BulkModel", `b${i}`, "Bulk", `Batch ${i % 4}`, (i * 7) % 101),
);

/** Ingest order: bulk first, so the flow rows are the newest under the default sort. */
export const ALL_ROWS: SeedResult[] = [...BULK_ROWS, ...FLOW_ROWS];

export type Metric = "confidenceAccuracy" | "sensitivity";

export function expectedCount(predicate: (r: SeedResult) => boolean): number {
  return ALL_ROWS.filter(predicate).length;
}
