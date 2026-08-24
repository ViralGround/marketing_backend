#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
# shellcheck source=lib/clone_guard.sh
source "${SCRIPT_DIR}/lib/clone_guard.sh"

dbops_verify_sentinel
dbops_evidence_dir

latest_migration="$(find "${REPO_ROOT}/src/main/resources/db/migration" -maxdepth 1 -type f -name 'V*__*.sql' \
  -printf '%f\n' | sed -E 's/^V(.+)__.*/\1/' | tr '_' '.' | sort -V | tail -n 1)"
[[ -n "$latest_migration" ]] || dbops_die "could not discover the latest migration version"

report="${EVIDENCE_DIR}/post-migration-verification.tsv"
checksum="${EVIDENCE_DIR}/post-migration-verification.sha256"
dbops_assert_artifacts_absent "$report" "$checksum"
PGOPTIONS='-c default_transaction_read_only=on -c statement_timeout=60000 -c lock_timeout=3000' \
  dbops_psql --quiet --tuples-only --no-align --field-separator=$'\t' \
  --set=expected_version="${latest_migration}" >"${report}" <<'SQL'
SELECT check_name, violation_count
FROM (
  SELECT 'flyway_failed_migration' AS check_name, COUNT(*)::bigint AS violation_count
  FROM flyway_schema_history WHERE success = FALSE
  UNION ALL
  SELECT 'flyway_latest_version_missing',
         CASE WHEN EXISTS (
           SELECT 1 FROM flyway_schema_history
           WHERE version = :'expected_version' AND success = TRUE
         ) THEN 0 ELSE 1 END
  UNION ALL
  SELECT 'invalid_member_status', COUNT(*)
  FROM members WHERE status NOT IN ('PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN')
  UNION ALL
  SELECT 'invalid_withdrawal_timestamp_pair', COUNT(*)
  FROM members
  WHERE (status = 'WITHDRAWN' AND withdrawn_at IS NULL)
     OR (status <> 'WITHDRAWN' AND withdrawn_at IS NOT NULL)
  UNION ALL
  SELECT 'invalid_application_status', COUNT(*)
  FROM campaign_applications
  WHERE status NOT IN (
    'PENDING', 'WITHDRAWN', 'APPROVED', 'REJECTED',
    'SUBMITTED', 'CHANGES_REQUESTED', 'SETTLED'
  )
  UNION ALL
  SELECT 'invalid_nonfinancial_completion_marker', COUNT(*)
  FROM campaign_applications
  WHERE content_approved_at IS NOT NULL
    AND (status <> 'SETTLED' OR reward_paid_amount IS NOT NULL OR settled_at IS NOT NULL)
  UNION ALL
  SELECT 'invalid_campaign_budget', COUNT(*)
  FROM campaigns
  WHERE reward_amount NOT BETWEEN 1 AND 100000000
     OR max_participants NOT BETWEEN 1 AND 10000
     OR total_budget <= 0
     OR total_budget::bigint <> reward_amount::bigint * max_participants::bigint
  UNION ALL
  SELECT 'duplicate_meta_provider_account', COUNT(*)
  FROM (
    SELECT provider_account_id FROM creator_instagram_connections
    WHERE provider_account_id IS NOT NULL
    GROUP BY provider_account_id HAVING COUNT(*) > 1
  ) duplicates
  UNION ALL
  SELECT 'unbalanced_payment_operation', COUNT(*)
  FROM (
    SELECT operation_id
    FROM payment_ledger_entries
    GROUP BY operation_id
    HAVING COUNT(*) <> 2
       OR COALESCE(SUM(amount) FILTER (WHERE direction = 'DEBIT'), 0)
          <> COALESCE(SUM(amount) FILTER (WHERE direction = 'CREDIT'), 0)
  ) unbalanced
  UNION ALL
  SELECT 'required_database_trigger_missing', COUNT(*)
  FROM (VALUES
    ('escrow_transactions', 'escrow_transactions_immutable'),
    ('payment_ledger_entries', 'payment_ledger_balance_guard'),
    ('payment_ledger_entries', 'payment_ledger_entries_immutable'),
    ('payment_webhook_events', 'payment_webhook_events_immutable'),
    ('audit_logs', 'trg_audit_logs_append_only'),
    ('member_consent_evidence', 'trg_member_consent_evidence_append_only'),
    ('contact_requests', 'trg_contact_privacy_evidence_immutable'),
    ('marketing_consent_events', 'trg_marketing_consent_events_append_only')
  ) expected(table_name, trigger_name)
  WHERE NOT EXISTS (
    SELECT 1
    FROM pg_trigger trigger
    JOIN pg_class table_relation ON table_relation.oid = trigger.tgrelid
    JOIN pg_namespace namespace ON namespace.oid = table_relation.relnamespace
    WHERE namespace.nspname = 'public'
      AND table_relation.relname = expected.table_name
      AND trigger.tgname = expected.trigger_name
      AND NOT trigger.tgisinternal
      AND trigger.tgenabled IN ('O', 'A')
  )
) checks
ORDER BY check_name;
SQL

cat "${report}"
violations="$(awk -F '\t' '{ total += $2 } END { print total + 0 }' "${report}")"
sha256sum "${report}" >"${checksum}"
[[ "$violations" == "0" ]] || dbops_die "post-migration verification found ${violations} violations"

printf 'Post-migration database invariants passed through Flyway V%s.\n' "${latest_migration}"
