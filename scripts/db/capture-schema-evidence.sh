#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
# shellcheck source=lib/clone_guard.sh
source "${SCRIPT_DIR}/lib/clone_guard.sh"

dbops_verify_sentinel
dbops_evidence_dir
dbops_require EVIDENCE_PHASE
case "${EVIDENCE_PHASE}" in before|after|restored) ;; *) dbops_die "EVIDENCE_PHASE must be before, after, or restored" ;; esac

phase_dir="${EVIDENCE_DIR}/${EVIDENCE_PHASE}"
dbops_assert_artifacts_absent "${phase_dir}"
mkdir -p "${phase_dir}"
readonly_opts='-c default_transaction_read_only=on -c statement_timeout=60000 -c lock_timeout=3000'

PGOPTIONS="$readonly_opts" dbops_psql --quiet --tuples-only --no-align --field-separator=$'\t' \
  >"${phase_dir}/database.tsv" <<'SQL'
SELECT 'database', current_database()
UNION ALL SELECT 'server_version', current_setting('server_version')
UNION ALL SELECT 'timezone', current_setting('timezone')
UNION ALL SELECT 'transaction_read_only', current_setting('transaction_read_only')
UNION ALL SELECT 'captured_at_utc', to_char(CURRENT_TIMESTAMP AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"');
SQL

PGOPTIONS="$readonly_opts" dbops_psql --quiet --tuples-only --no-align --field-separator=$'\t' \
  >"${phase_dir}/extensions.tsv" <<'SQL'
SELECT extname, extversion FROM pg_extension ORDER BY extname;
SQL

PGOPTIONS="$readonly_opts" dbops_psql --quiet --tuples-only --no-align --field-separator=$'\t' \
  >"${phase_dir}/columns.tsv" <<'SQL'
SELECT table_schema, table_name, ordinal_position, column_name, data_type,
       is_nullable, COALESCE(column_default, '')
FROM information_schema.columns
WHERE table_schema = 'public'
ORDER BY table_name, ordinal_position;
SQL

PGOPTIONS="$readonly_opts" dbops_psql --quiet --tuples-only --no-align --field-separator=$'\t' \
  >"${phase_dir}/constraints.tsv" <<'SQL'
SELECT n.nspname, c.relname, con.conname, con.contype, con.convalidated,
       pg_get_constraintdef(con.oid, true)
FROM pg_constraint con
JOIN pg_class c ON c.oid = con.conrelid
JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'public'
ORDER BY c.relname, con.conname;
SQL

PGOPTIONS="$readonly_opts" dbops_psql --quiet --tuples-only --no-align --field-separator=$'\t' \
  >"${phase_dir}/indexes.tsv" <<'SQL'
SELECT schemaname, tablename, indexname, indexdef
FROM pg_indexes WHERE schemaname = 'public'
ORDER BY tablename, indexname;
SQL

PGOPTIONS="$readonly_opts" dbops_psql --quiet --tuples-only --no-align --field-separator=$'\t' \
  >"${phase_dir}/triggers.tsv" <<'SQL'
SELECT c.relname, t.tgname, t.tgenabled, pg_get_triggerdef(t.oid, true)
FROM pg_trigger t
JOIN pg_class c ON c.oid = t.tgrelid
JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'public' AND NOT t.tgisinternal
ORDER BY c.relname, t.tgname;
SQL

PGOPTIONS="$readonly_opts" dbops_psql --quiet --tuples-only --no-align --field-separator=$'\t' \
  >"${phase_dir}/sequences.tsv" <<'SQL'
SELECT schemaname, sequencename, start_value, min_value, max_value, increment_by, cycle, last_value
FROM pg_sequences WHERE schemaname = 'public'
ORDER BY sequencename;
SQL

PGOPTIONS="$readonly_opts" dbops_psql --quiet --tuples-only --no-align --field-separator=$'\t' \
  >"${phase_dir}/sequence-integrity.tsv" <<'SQL'
SELECT format(
  'SELECT %L, last_value::bigint, COALESCE((SELECT max(%I)::bigint FROM %I.%I), 0),'
  ' CASE WHEN last_value::bigint >= COALESCE((SELECT max(%I)::bigint FROM %I.%I), 0)'
  ' THEN 0 ELSE 1 END FROM %s;',
  pg_get_serial_sequence(format('%I.%I', table_schema, table_name), column_name),
  column_name, table_schema, table_name,
  column_name, table_schema, table_name,
  pg_get_serial_sequence(format('%I.%I', table_schema, table_name), column_name)::regclass
)
FROM information_schema.columns
WHERE table_schema = 'public'
  AND pg_get_serial_sequence(format('%I.%I', table_schema, table_name), column_name) IS NOT NULL
ORDER BY table_name, ordinal_position
\gexec
SQL

PGOPTIONS="$readonly_opts" dbops_psql --quiet --tuples-only --no-align --field-separator=$'\t' \
  >"${phase_dir}/fk-orphans.tsv" <<'SQL'
WITH foreign_keys AS (
  SELECT con.oid, child.oid AS child_oid, parent.oid AS parent_oid,
         child_ns.nspname AS child_schema, child.relname AS child_table,
         parent_ns.nspname AS parent_schema, parent.relname AS parent_table,
         con.conname, con.conkey, con.confkey
  FROM pg_constraint con
  JOIN pg_class child ON child.oid = con.conrelid
  JOIN pg_namespace child_ns ON child_ns.oid = child.relnamespace
  JOIN pg_class parent ON parent.oid = con.confrelid
  JOIN pg_namespace parent_ns ON parent_ns.oid = parent.relnamespace
  WHERE con.contype = 'f' AND child_ns.nspname = 'public'
), joins AS (
  SELECT fk.*,
         string_agg(format('c.%I = p.%I', child_att.attname, parent_att.attname),
                    ' AND ' ORDER BY key_part.ordinality) AS join_sql,
         string_agg(format('c.%I IS NOT NULL', child_att.attname),
                    ' AND ' ORDER BY key_part.ordinality) AS child_present_sql,
         (array_agg(parent_att.attname ORDER BY key_part.ordinality))[1] AS parent_probe
  FROM foreign_keys fk
  CROSS JOIN LATERAL unnest(fk.conkey, fk.confkey) WITH ORDINALITY
       AS key_part(child_attnum, parent_attnum, ordinality)
  JOIN pg_attribute child_att
    ON child_att.attrelid = fk.child_oid AND child_att.attnum = key_part.child_attnum
  JOIN pg_attribute parent_att
    ON parent_att.attrelid = fk.parent_oid AND parent_att.attnum = key_part.parent_attnum
  GROUP BY fk.oid, fk.child_oid, fk.parent_oid, fk.child_schema, fk.child_table, fk.parent_schema,
           fk.parent_table, fk.conname, fk.conkey, fk.confkey
)
SELECT format(
  'SELECT %L, count(*)::bigint FROM %I.%I c LEFT JOIN %I.%I p ON %s'
  ' WHERE %s AND p.%I IS NULL;',
  child_table || '.' || conname, child_schema, child_table,
  parent_schema, parent_table, join_sql, child_present_sql, parent_probe
)
FROM joins
ORDER BY child_table, conname
\gexec
SQL

PGOPTIONS="$readonly_opts" dbops_psql --quiet --tuples-only --no-align --field-separator=$'\t' \
  >"${phase_dir}/row-counts.tsv" <<'SQL'
SELECT format(
  'SELECT %L AS table_name, count(*)::bigint AS row_count FROM %I.%I;',
  table_name, table_schema, table_name
)
FROM information_schema.tables
WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
ORDER BY table_name
\gexec
SQL

PGOPTIONS="$readonly_opts" dbops_psql --quiet --tuples-only --no-align --field-separator=$'\t' \
  >"${phase_dir}/financial-aggregates.tsv" <<'SQL'
SELECT 'campaigns', COUNT(*)::text,
       COALESCE(SUM(reward_amount), 0)::text,
       COALESCE(SUM(total_budget), 0)::text
FROM campaigns
UNION ALL
SELECT 'escrow_transactions', COUNT(*)::text,
       COALESCE(SUM(CASE WHEN type = 'DEPOSIT' THEN amount ELSE 0 END), 0)::text,
       COALESCE(SUM(CASE WHEN type IN ('RELEASE', 'REFUND') THEN amount ELSE 0 END), 0)::text
FROM escrow_transactions;
SQL

if PGOPTIONS="$readonly_opts" dbops_psql --quiet --tuples-only --no-align --field-separator=$'\t' \
  -c "SELECT installed_rank, version, description, type, COALESCE(checksum::text, ''), success FROM flyway_schema_history ORDER BY installed_rank" \
  >"${phase_dir}/flyway-history.tsv" 2>"${phase_dir}/flyway-history.error"; then
  rm -f "${phase_dir}/flyway-history.error"
else
  printf 'flyway_schema_history is absent (expected before a one-time legacy baseline).\n' \
    >"${phase_dir}/flyway-history.tsv"
  rm -f "${phase_dir}/flyway-history.error"
fi

(
  cd "${REPO_ROOT}"
  sha256sum src/main/resources/db/migration/V*__*.sql
) >"${phase_dir}/migration-files.sha256"

(
  cd "${phase_dir}"
  sha256sum columns.tsv constraints.tsv indexes.tsv triggers.tsv >schema-fingerprint.sha256
  sha256sum database.tsv extensions.tsv columns.tsv constraints.tsv indexes.tsv \
    triggers.tsv sequences.tsv sequence-integrity.tsv fk-orphans.tsv row-counts.tsv \
    financial-aggregates.tsv flyway-history.tsv migration-files.sha256 \
    >evidence-files.sha256
)

printf 'Captured non-PII %s evidence in %s\n' "${EVIDENCE_PHASE}" "${phase_dir}"
