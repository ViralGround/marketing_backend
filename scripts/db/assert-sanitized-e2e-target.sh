#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/clone_guard.sh
source "${SCRIPT_DIR}/lib/clone_guard.sh"

[[ "${CLONE_KIND:-}" == "sanitized" ]] ||
  dbops_die "staging E2E requires CLONE_KIND=sanitized"
dbops_require CLONE_EVIDENCE_SEAL_SHA256
[[ "${CLONE_EVIDENCE_SEAL_SHA256}" =~ ^[0-9a-f]{64}$ ]] ||
  dbops_die "CLONE_EVIDENCE_SEAL_SHA256 must be lowercase 64-hex"
dbops_verify_sentinel

state="$(PGOPTIONS='-c default_transaction_read_only=on -c statement_timeout=10000 -c lock_timeout=3000' \
  dbops_psql --quiet --tuples-only --no-align \
    --set=sentinel_id="${CLONE_SENTINEL_ID}" \
    --set=source_snapshot_id="${SOURCE_SNAPSHOT_ID}" \
    --set=release_id="${RELEASE_ID}" \
    --set=evidence_seal_sha256="${CLONE_EVIDENCE_SEAL_SHA256}" <<'SQL'
SELECT CASE
  WHEN baseline_started_at IS NULL THEN 'baseline-not-started'
  WHEN baseline_completed_at IS NULL THEN 'baseline-not-completed'
  WHEN evidence_seal_sha256 <> :'evidence_seal_sha256' THEN 'evidence-seal-mismatch'
  ELSE 'ready'
END
FROM preprod_guard.clone_sentinel
WHERE sentinel_id = :'sentinel_id'
  AND clone_kind = 'sanitized'
  AND source_snapshot_id = :'source_snapshot_id'
  AND release_id = :'release_id'
  AND destroyed_at IS NULL
  AND expires_at > CURRENT_TIMESTAMP;
SQL
)" || dbops_die "sanitized E2E readiness query failed"

[[ "$state" == "ready" ]] ||
  dbops_die "sanitized E2E target is not migration-complete for this release (${state:-missing})"

printf 'Sanitized E2E target guard passed for release %s. No data was changed.\n' "${RELEASE_ID}"
