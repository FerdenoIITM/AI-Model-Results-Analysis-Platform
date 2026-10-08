#!/bin/sh
# Applies the real migrations in version order, then the test-only seed users.
set -e
for file in $(ls /migrations/*.sql | sort -V); do
  echo "Applying $file"
  psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -f "$file"
done
psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -f /seed/seed.sql
