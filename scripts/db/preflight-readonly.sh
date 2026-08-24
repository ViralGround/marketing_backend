#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/clone_guard.sh
source "${SCRIPT_DIR}/lib/clone_guard.sh"

dbops_verify_sentinel
dbops_evidence_dir

report="${EVIDENCE_DIR}/preflight-${CLONE_KIND}.tsv"
metadata="${EVIDENCE_DIR}/preflight-connection.txt"
checksum="${EVIDENCE_DIR}/preflight-sha256.txt"
dbops_assert_artifacts_absent "$report" "$metadata" "$checksum"

PGOPTIONS='-c default_transaction_read_only=on -c statement_timeout=30000 -c lock_timeout=3000' \
  dbops_psql --quiet --tuples-only --no-align --field-separator=$'\t' >"${metadata}" <<'SQL'
SELECT 'database', current_database()
UNION ALL SELECT 'server_address', COALESCE(inet_server_addr()::text, 'local-socket')
UNION ALL SELECT 'server_port', inet_server_port()::text
UNION ALL SELECT 'server_version', current_setting('server_version')
UNION ALL SELECT 'transaction_read_only', current_setting('transaction_read_only')
UNION ALL SELECT 'current_user', current_user
UNION ALL SELECT 'captured_at_utc', to_char(CURRENT_TIMESTAMP AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"');
SQL

PGOPTIONS='-c default_transaction_read_only=on -c statement_timeout=30000 -c lock_timeout=3000' \
  dbops_psql --quiet --tuples-only --no-align --field-separator=$'\t' >"${report}" <<'SQL'
SELECT check_name, severity, blocking_count
FROM (
  SELECT 'v3_nonpositive_escrow_amount' AS check_name, 'BLOCKER' AS severity,
         COUNT(*)::bigint AS blocking_count
  FROM escrow_transactions WHERE amount <= 0
  UNION ALL
  SELECT 'v3_unknown_escrow_type', 'BLOCKER', COUNT(*)
  FROM escrow_transactions WHERE type NOT IN ('DEPOSIT', 'RELEASE', 'REFUND')
  UNION ALL
  SELECT 'v3_duplicate_campaign_deposit', 'BLOCKER', COUNT(*)
  FROM (
    SELECT campaign_id FROM escrow_transactions WHERE type = 'DEPOSIT'
    GROUP BY campaign_id HAVING COUNT(*) > 1
  ) duplicates
  UNION ALL
  SELECT 'v3_duplicate_campaign_refund', 'BLOCKER', COUNT(*)
  FROM (
    SELECT campaign_id FROM escrow_transactions WHERE type = 'REFUND'
    GROUP BY campaign_id HAVING COUNT(*) > 1
  ) duplicates
  UNION ALL
  SELECT 'v3_duplicate_application_release', 'BLOCKER', COUNT(*)
  FROM (
    SELECT campaign_id, application_id FROM escrow_transactions
    WHERE type = 'RELEASE'
    GROUP BY campaign_id, application_id HAVING COUNT(*) > 1
  ) duplicates
  UNION ALL
  SELECT 'v3_negative_running_balance', 'BLOCKER', COUNT(*)
  FROM (
    SELECT SUM(CASE WHEN type = 'DEPOSIT' THEN amount ELSE -amount END)
             OVER (PARTITION BY campaign_id ORDER BY created_at, id) AS running_balance
    FROM escrow_transactions
  ) balances
  WHERE running_balance < 0
  UNION ALL
  SELECT 'v4_duplicate_meta_provider_account', 'BLOCKER', COUNT(*)
  FROM (
    SELECT provider_account_id FROM creator_instagram_connections
    WHERE provider_account_id IS NOT NULL
    GROUP BY provider_account_id HAVING COUNT(*) > 1
  ) duplicates
  UNION ALL
  SELECT 'v9_unknown_member_status', 'BLOCKER', COUNT(*)
  FROM members WHERE status NOT IN ('PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN')
  UNION ALL
  SELECT 'v9_preexisting_withdrawn_member_without_timestamp', 'BLOCKER', COUNT(*)
  FROM members WHERE status = 'WITHDRAWN'
  UNION ALL
  SELECT 'v9_unknown_application_status', 'BLOCKER', COUNT(*)
  FROM campaign_applications
  WHERE status NOT IN ('PENDING', 'WITHDRAWN', 'APPROVED', 'REJECTED', 'SUBMITTED', 'CHANGES_REQUESTED', 'SETTLED')
  UNION ALL
  SELECT 'v11_invalid_campaign_budget', 'BLOCKER', COUNT(*)
  FROM campaigns
  WHERE reward_amount NOT BETWEEN 1 AND 100000000
     OR max_participants NOT BETWEEN 1 AND 10000
     OR total_budget <= 0
     OR total_budget::bigint <> reward_amount::bigint * max_participants::bigint
  UNION ALL
  SELECT 'v11_homepage_will_be_cleared', 'WARNING', COUNT(*)
  FROM company_profiles
  WHERE homepage IS NOT NULL
    AND (char_length(homepage) > 500 OR homepage !~ '^https://'
         OR homepage <> btrim(homepage) OR homepage ~ '[[:cntrl:]]'
         OR homepage ~ '^https://[^/?#]*@')
) checks
ORDER BY severity, check_name;
SQL

cat "${report}"
blockers="$(awk -F '\t' '$2 == "BLOCKER" { total += $3 } END { print total + 0 }' "${report}")"
sha256sum "${metadata}" "${report}" >"${checksum}"

if [[ "$blockers" != "0" ]]; then
  dbops_die "preflight found ${blockers} blocking legacy rows; migration must not run"
fi

printf 'Preflight passed with zero blockers. Evidence: %s\n' "${report}"
