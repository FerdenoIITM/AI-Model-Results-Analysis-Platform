import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import pg from "pg";
import { ALL_ROWS } from "./dataset";
import { API_URL, DB_URL, INGEST_KEY } from "./env";

const SEED_SQL = resolve(dirname(fileURLToPath(import.meta.url)), "../fixtures/seed.sql");

/**
 * Restores the canonical test state: only the seed accounts, no results, then the
 * dataset ingested one by one through the real ingest endpoint.
 */
export async function resetAndSeed(): Promise<void> {
  const client = new pg.Client({ connectionString: DB_URL });
  await client.connect();
  try {
    await client.query("TRUNCATE users, results, result_chunks CASCADE");
    await client.query(readFileSync(SEED_SQL, "utf8"));
  } finally {
    await client.end();
  }
  for (const row of ALL_ROWS) {
    const res = await fetch(`${API_URL}/results`, {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-Ingest-Key": INGEST_KEY },
      body: JSON.stringify(row),
    });
    if (res.status !== 201) {
      throw new Error(`Seeding failed for ${row.modelName} ${row.version}: ${res.status} ${await res.text()}`);
    }
  }
}

/** Runs a SQL query against the test database (for assertions only). */
export async function sql<T extends pg.QueryResultRow = pg.QueryResultRow>(text: string, params: unknown[] = []) {
  const client = new pg.Client({ connectionString: DB_URL });
  await client.connect();
  try {
    return (await client.query<T>(text, params)).rows;
  } finally {
    await client.end();
  }
}
