#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/clone_guard.sh
source "${SCRIPT_DIR}/lib/clone_guard.sh"

# This capture must run before the first sanitized-clone UPDATE. It intentionally
# contains only structural metadata, counts, and numeric aggregates so the exact
# and sanitized restores can be compared without copying row values into evidence.
dbops_verify_sentinel
dbops_evidence_dir
report="${EVIDENCE_DIR}/source-origin-fingerprint.tsv"
checksum="${report}.sha256"
dbops_assert_artifacts_absent "$report" "$checksum"

PGOPTIONS='-c default_transaction_read_only=on -c statement_timeout=300000 -c lock_timeout=3000' \
  dbops_psql --quiet --tuples-only --no-align --field-separator=$'\t' >"$report" <<'SQL'
WITH structural_items AS (
  SELECT format('relation|%I.%I|%s', namespace.nspname, relation.relname, relation.relkind) AS item
  FROM pg_class relation
  JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
  WHERE namespace.nspname NOT IN ('information_schema', 'preprod_guard')
    AND namespace.nspname !~ '^pg_'
    AND relation.relkind IN ('r', 'p', 'v', 'm', 'f', 'S')
  UNION ALL
  SELECT format('column|%I.%I|%s|%s|%s',
                table_schema, table_name, column_name, data_type, is_nullable)
  FROM information_schema.columns
  WHERE table_schema NOT IN ('information_schema', 'preprod_guard')
    AND table_schema !~ '^pg_'
)
SELECT 'schema-structural-md5', md5(string_agg(item, E'\n' ORDER BY item))
FROM structural_items;

SELECT format(
  'SELECT %L, count(*)::bigint::text FROM %I.%I;',
  'row-count|' || namespace.nspname || '.' || relation.relname,
  namespace.nspname,
  relation.relname
)
FROM pg_class relation
JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
WHERE namespace.nspname NOT IN ('information_schema', 'preprod_guard')
  AND namespace.nspname !~ '^pg_'
  AND relation.relkind IN ('r', 'p')
  AND NOT relation.relispartition
ORDER BY namespace.nspname, relation.relname
\gexec

SELECT 'financial|campaigns',
       COUNT(*)::text || ':' || COALESCE(SUM(reward_amount), 0)::text || ':' ||
       COALESCE(SUM(total_budget), 0)::text
FROM public.campaigns
UNION ALL
SELECT 'financial|escrow_transactions',
       COUNT(*)::text || ':' ||
       COALESCE(SUM(CASE WHEN type = 'DEPOSIT' THEN amount ELSE 0 END), 0)::text || ':' ||
       COALESCE(SUM(CASE WHEN type IN ('RELEASE', 'REFUND') THEN amount ELSE 0 END), 0)::text
FROM public.escrow_transactions
ORDER BY 1;
SQL

LC_ALL=C sort -o "$report" "$report"
sha256sum "$report" >"$checksum"
printf 'Captured pre-mask non-sensitive source-origin fingerprint: %s\n' "$report"
