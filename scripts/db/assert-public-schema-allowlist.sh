#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/clone_guard.sh
source "${SCRIPT_DIR}/lib/clone_guard.sh"

dbops_verify_sentinel
dbops_evidence_dir

allowlist_source="${SCRIPT_DIR}/public-schema-allowlist.tsv"
[[ -f "$allowlist_source" && ! -L "$allowlist_source" ]] ||
  dbops_die "public schema allowlist is missing or is a symbolic link"

mode="${PUBLIC_SCHEMA_ALLOWLIST_MODE:-}"
if [[ -z "$mode" ]]; then
  history_state="$(PGOPTIONS='-c default_transaction_read_only=on -c statement_timeout=10000 -c lock_timeout=3000' \
    dbops_psql --quiet --tuples-only --no-align -c \
      "SELECT CASE WHEN to_regclass('public.flyway_schema_history') IS NULL THEN 'legacy-subset' ELSE 'latest-exact' END")"
  mode="$history_state"
fi
case "$mode" in
  legacy-subset|latest-exact) ;;
  *) dbops_die "PUBLIC_SCHEMA_ALLOWLIST_MODE must be legacy-subset or latest-exact" ;;
esac

if ! LC_ALL=C sort -c -u "$allowlist_source"; then
  dbops_die "public schema allowlist must be bytewise sorted and unique"
fi
if awk -F '\t' 'NF != 2 || $1 !~ /^[a-z][a-z0-9_]*$/ || $2 !~ /^[a-z][a-z0-9_]*$/ { bad = 1 } END { exit bad }' \
    "$allowlist_source"; then
  :
else
  dbops_die "public schema allowlist contains an invalid table or column identifier"
fi

allowlist_evidence="${EVIDENCE_DIR}/public-schema-allowlist.tsv"
allowlist_checksum="${EVIDENCE_DIR}/public-schema-allowlist.sha256"
if [[ ! -e "$allowlist_evidence" && ! -L "$allowlist_evidence" ]]; then
  dbops_assert_artifacts_absent "$allowlist_checksum"
  cp -- "$allowlist_source" "$allowlist_evidence"
  (
    cd "${EVIDENCE_DIR}"
    sha256sum public-schema-allowlist.tsv >public-schema-allowlist.sha256
  )
else
  [[ -f "$allowlist_evidence" && ! -L "$allowlist_evidence" \
      && -f "$allowlist_checksum" && ! -L "$allowlist_checksum" ]] ||
    dbops_die "public schema allowlist evidence is partial or unsafe"
  cmp -s "$allowlist_source" "$allowlist_evidence" ||
    dbops_die "sealed-run public schema allowlist differs from the repository allowlist"
  (
    cd "${EVIDENCE_DIR}"
    sha256sum --check public-schema-allowlist.sha256 >/dev/null
  ) || dbops_die "public schema allowlist evidence checksum is invalid"
fi

table_inventory="${EVIDENCE_DIR}/public-table-inventory-${mode}.tsv"
table_inventory_checksum="${table_inventory}.sha256"
relation_inventory="${EVIDENCE_DIR}/public-relation-kind-inventory-${mode}.tsv"
relation_inventory_checksum="${relation_inventory}.sha256"
inventory="${EVIDENCE_DIR}/public-schema-inventory-${mode}.tsv"
inventory_checksum="${inventory}.sha256"
object_safety_report="${EVIDENCE_DIR}/database-object-safety-${mode}.tsv"
object_safety_checksum="${object_safety_report}.sha256"
report="${EVIDENCE_DIR}/public-schema-allowlist-check-${mode}.tsv"
report_checksum="${report}.sha256"
dbops_assert_artifacts_absent \
  "$table_inventory" "$table_inventory_checksum" \
  "$relation_inventory" "$relation_inventory_checksum" \
  "$inventory" "$inventory_checksum" \
  "$object_safety_report" "$object_safety_checksum" \
  "$report" "$report_checksum"

PGOPTIONS='-c default_transaction_read_only=on -c statement_timeout=60000 -c lock_timeout=3000' \
  dbops_psql --quiet --tuples-only --no-align >"$table_inventory" <<'SQL'
SELECT relation.relname
FROM pg_class relation
JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
WHERE namespace.nspname = 'public'
  AND relation.relkind IN ('r', 'p', 'f', 'm', 'v')
  AND relation.relname <> 'flyway_schema_history'
ORDER BY relation.relname COLLATE "C";
SQL

PGOPTIONS='-c default_transaction_read_only=on -c statement_timeout=60000 -c lock_timeout=3000' \
  dbops_psql --quiet --tuples-only --no-align --field-separator=$'\t' >"$relation_inventory" <<'SQL'
SELECT relation.relname, relation.relkind
FROM pg_class relation
JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
WHERE namespace.nspname = 'public'
  AND relation.relkind IN ('r', 'p', 'f', 'm', 'v')
  AND relation.relname <> 'flyway_schema_history'
ORDER BY relation.relname COLLATE "C";
SQL

PGOPTIONS='-c default_transaction_read_only=on -c statement_timeout=60000 -c lock_timeout=3000' \
  dbops_psql --quiet --tuples-only --no-align --field-separator=$'\t' >"$inventory" <<'SQL'
SELECT relation.relname, attribute.attname
FROM pg_class relation
JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
JOIN pg_attribute attribute ON attribute.attrelid = relation.oid
WHERE namespace.nspname = 'public'
  AND relation.relkind IN ('r', 'p', 'f', 'm', 'v')
  AND relation.relname <> 'flyway_schema_history'
  AND attribute.attnum > 0
  AND NOT attribute.attisdropped
ORDER BY relation.relname COLLATE "C", attribute.attname COLLATE "C";
SQL

LC_ALL=C sort -c -u "$table_inventory" || dbops_die "public table inventory is not sorted and unique"
LC_ALL=C sort -c -u "$relation_inventory" || dbops_die "public relation inventory is not sorted and unique"
LC_ALL=C sort -c -u "$inventory" || dbops_die "public schema inventory is not sorted and unique"

# This is the last gate before sanitizer DML. Names and columns alone are not a
# sufficient safety boundary: an updatable view or foreign table can redirect an
# UPDATE, and an unclassified trigger/routine can copy the original value before it
# is masked. Only local ordinary/partitioned application tables and the exact
# repository-owned trigger/routine set are permitted.
PGOPTIONS='-c default_transaction_read_only=on -c statement_timeout=60000 -c lock_timeout=3000' \
  dbops_psql --quiet --tuples-only --no-align --field-separator=$'\t' \
    --set=allowlist_mode="$mode" >"$object_safety_report" <<'SQL'
WITH approved_triggers(
  table_name, trigger_name, routine_name, trigger_type,
  is_constraint, is_deferrable, is_initially_deferred
) AS (
  VALUES
    ('audit_logs', 'trg_audit_logs_append_only', 'reject_audit_log_mutation', 27, false, false, false),
    ('contact_requests', 'trg_contact_privacy_evidence_immutable', 'reject_contact_privacy_evidence_mutation', 19, false, false, false),
    ('escrow_transactions', 'escrow_transactions_immutable', 'reject_payment_record_mutation', 27, false, false, false),
    ('marketing_consent_events', 'trg_marketing_consent_events_append_only', 'reject_marketing_consent_event_mutation', 27, false, false, false),
    ('member_consent_evidence', 'trg_member_consent_evidence_append_only', 'reject_member_consent_evidence_mutation', 27, false, false, false),
    ('payment_ledger_entries', 'payment_ledger_balance_guard', 'validate_payment_ledger_balance', 5, true, true, true),
    ('payment_ledger_entries', 'payment_ledger_entries_immutable', 'reject_payment_record_mutation', 27, false, false, false),
    ('payment_webhook_events', 'payment_webhook_events_immutable', 'reject_payment_record_mutation', 27, false, false, false)
), approved_routines(routine_name, normalized_source_md5) AS (
  VALUES
    ('reject_audit_log_mutation', '09c635bac3e2d672d7f37125198a436d'),
    ('reject_contact_privacy_evidence_mutation', 'a71ff059f8557a7d3809e74d728a62d2'),
    ('reject_marketing_consent_event_mutation', '16c9ddcb4322010964f9add9ac3caf1c'),
    ('reject_member_consent_evidence_mutation', '84744a45211e211fd3f669f2dbe02a58'),
    ('reject_payment_record_mutation', '437ba3a115c2e47c5ffa9c752a16d199'),
    ('validate_payment_ledger_balance', '64fe8f5421dd15919bb14a85b3073716')
), actual_triggers AS (
  SELECT table_relation.relname AS table_name,
         trigger_value.tgname AS trigger_name,
         routine.proname AS routine_name,
         routine_namespace.nspname AS routine_schema,
         trigger_value.tgenabled AS enabled,
         trigger_value.tgtype::integer AS trigger_type,
         (trigger_value.tgconstraint <> 0) AS is_constraint,
         trigger_value.tgdeferrable AS is_deferrable,
         trigger_value.tginitdeferred AS is_initially_deferred
  FROM pg_trigger trigger_value
  JOIN pg_class table_relation ON table_relation.oid = trigger_value.tgrelid
  JOIN pg_namespace table_namespace ON table_namespace.oid = table_relation.relnamespace
  JOIN pg_proc routine ON routine.oid = trigger_value.tgfoid
  JOIN pg_namespace routine_namespace ON routine_namespace.oid = routine.pronamespace
  WHERE table_namespace.nspname = 'public'
    AND NOT trigger_value.tgisinternal
), actual_routines AS (
  SELECT routine.proname AS routine_name,
         routine.pronargs,
         routine.prorettype,
         language.lanname AS language_name,
         routine.prosecdef,
         routine.proconfig,
         pg_get_userbyid(routine.proowner) AS owner_name,
         md5(regexp_replace(btrim(routine.prosrc), '[[:space:]]+', ' ', 'g')) AS normalized_source_md5
  FROM pg_proc routine
  JOIN pg_namespace namespace ON namespace.oid = routine.pronamespace
  JOIN pg_language language ON language.oid = routine.prolang
  WHERE namespace.nspname = 'public'
)
SELECT check_name, violation_count
FROM (
  SELECT 'unexpected_non_system_schema' AS check_name, COUNT(*)::bigint AS violation_count
  FROM pg_namespace namespace
  WHERE namespace.nspname NOT IN ('public', 'preprod_guard', 'information_schema')
    AND namespace.nspname !~ '^pg_'
  UNION ALL
  SELECT 'foreign_data_wrapper', COUNT(*)::bigint FROM pg_foreign_data_wrapper
  UNION ALL
  SELECT 'foreign_server', COUNT(*)::bigint FROM pg_foreign_server
  UNION ALL
  SELECT 'foreign_user_mapping', COUNT(*)::bigint FROM pg_user_mappings
  UNION ALL
  SELECT 'nonlocal_public_application_relation', COUNT(*)::bigint
  FROM pg_class relation
  JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
  WHERE namespace.nspname = 'public'
    AND relation.relkind IN ('f', 'm', 'v')
    AND relation.relname <> 'flyway_schema_history'
  UNION ALL
  SELECT 'unexpected_public_user_trigger', COUNT(*)::bigint
  FROM actual_triggers actual
  WHERE NOT EXISTS (
    SELECT 1 FROM approved_triggers approved
    WHERE approved.table_name = actual.table_name
      AND approved.trigger_name = actual.trigger_name
      AND approved.routine_name = actual.routine_name
      AND actual.routine_schema = 'public'
      AND approved.trigger_type = actual.trigger_type
      AND approved.is_constraint = actual.is_constraint
      AND approved.is_deferrable = actual.is_deferrable
      AND approved.is_initially_deferred = actual.is_initially_deferred
  )
  UNION ALL
  SELECT 'missing_or_disabled_latest_user_trigger', COUNT(*)::bigint
  FROM approved_triggers approved
  WHERE :'allowlist_mode' = 'latest-exact'
    AND NOT EXISTS (
      SELECT 1 FROM actual_triggers actual
      WHERE actual.table_name = approved.table_name
        AND actual.trigger_name = approved.trigger_name
        AND actual.routine_name = approved.routine_name
        AND actual.routine_schema = 'public'
        AND actual.trigger_type = approved.trigger_type
        AND actual.is_constraint = approved.is_constraint
        AND actual.is_deferrable = approved.is_deferrable
        AND actual.is_initially_deferred = approved.is_initially_deferred
        AND actual.enabled IN ('O', 'A')
    )
  UNION ALL
  SELECT 'unexpected_public_user_routine', COUNT(*)::bigint
  FROM actual_routines actual
  WHERE NOT EXISTS (
    SELECT 1 FROM approved_routines approved
    WHERE approved.routine_name = actual.routine_name
      AND approved.normalized_source_md5 = actual.normalized_source_md5
      AND actual.pronargs = 0
      AND actual.prorettype = 'trigger'::regtype
      AND actual.language_name = 'plpgsql'
      AND NOT actual.prosecdef
      AND actual.proconfig IS NULL
      AND actual.owner_name = current_user
  )
  UNION ALL
  SELECT 'missing_latest_user_routine', COUNT(*)::bigint
  FROM approved_routines approved
  WHERE :'allowlist_mode' = 'latest-exact'
    AND NOT EXISTS (
      SELECT 1 FROM actual_routines actual
      WHERE actual.routine_name = approved.routine_name
        AND approved.normalized_source_md5 = actual.normalized_source_md5
        AND actual.pronargs = 0
        AND actual.prorettype = 'trigger'::regtype
        AND actual.language_name = 'plpgsql'
        AND NOT actual.prosecdef
        AND actual.proconfig IS NULL
        AND actual.owner_name = current_user
    )
) checks
ORDER BY check_name;
SQL
object_safety_violations="$(awk -F '\t' '{ total += $2 } END { print total + 0 }' \
  "$object_safety_report")"
allowed_tables="$(mktemp)"
unknown_tables="$(mktemp)"
missing_tables="$(mktemp)"
unknown_columns="$(mktemp)"
missing_columns="$(mktemp)"
cleanup() {
  rm -f -- "$allowed_tables" "$unknown_tables" "$missing_tables" \
    "$unknown_columns" "$missing_columns"
}
trap cleanup EXIT
cut -f1 "$allowlist_source" | LC_ALL=C sort -u >"$allowed_tables"
comm -23 "$table_inventory" "$allowed_tables" >"$unknown_tables"
comm -13 "$table_inventory" "$allowed_tables" >"$missing_tables"
comm -23 "$inventory" "$allowlist_source" >"$unknown_columns"
comm -13 "$inventory" "$allowlist_source" >"$missing_columns"
unknown_table_count="$(wc -l <"$unknown_tables" | tr -d ' ')"
unknown_column_count="$(wc -l <"$unknown_columns" | tr -d ' ')"
missing_table_count="$(wc -l <"$missing_tables" | tr -d ' ')"
missing_column_count="$(wc -l <"$missing_columns" | tr -d ' ')"
unknown_count="$((unknown_table_count + unknown_column_count))"
missing_count="$((missing_table_count + missing_column_count))"

{
  printf 'check_name\tviolation_count\n'
  printf 'unknown_public_table\t%s\n' "$unknown_table_count"
  printf 'unknown_public_column\t%s\n' "$unknown_column_count"
  printf 'unknown_public_table_or_column\t%s\n' "$unknown_count"
  if [[ "$mode" == "latest-exact" ]]; then
    printf 'missing_latest_public_table\t%s\n' "$missing_table_count"
    printf 'missing_latest_public_column\t%s\n' "$missing_column_count"
    printf 'missing_latest_public_table_or_column\t%s\n' "$missing_count"
  else
    printf 'missing_latest_public_table_not_applicable\t0\n'
    printf 'missing_latest_public_column_not_applicable\t0\n'
    printf 'missing_latest_public_table_or_column_not_applicable\t0\n'
  fi
} >"$report"
(
  cd "${EVIDENCE_DIR}"
  sha256sum "$(basename "$table_inventory")" >"$(basename "$table_inventory_checksum")"
  sha256sum "$(basename "$relation_inventory")" >"$(basename "$relation_inventory_checksum")"
  sha256sum "$(basename "$inventory")" >"$(basename "$inventory_checksum")"
  sha256sum "$(basename "$object_safety_report")" >"$(basename "$object_safety_checksum")"
  sha256sum "$(basename "$report")" >"$(basename "$report_checksum")"
)

cat "$report"
cat "$object_safety_report"
if [[ "$object_safety_violations" != "0" ]]; then
  dbops_die "database object safety boundary found ${object_safety_violations} violations; sanitization is forbidden"
fi
if [[ "$unknown_count" != "0" ]]; then
  dbops_die "public schema contains ${unknown_count} unknown table/column entries; sanitization is forbidden"
fi
if [[ "$mode" == "latest-exact" && "$missing_count" != "0" ]]; then
  dbops_die "public schema is missing ${missing_count} latest application table/column entries"
fi

cleanup
trap - EXIT
printf 'Public schema allowlist passed in %s mode; inventory and checksums are evidence-bound.\n' "$mode"
