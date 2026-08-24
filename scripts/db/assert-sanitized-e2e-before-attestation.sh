#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/clone_guard.sh
source "${SCRIPT_DIR}/lib/clone_guard.sh"

[[ "${CLONE_KIND:-}" == "sanitized" ]] ||
  dbops_die "before-E2E attestation requires CLONE_KIND=sanitized"
dbops_require E2E_BEFORE_EVIDENCE_DIR
dbops_require CLONE_E2E_BEFORE_EVIDENCE_SEAL_SHA256
dbops_require CLONE_EVIDENCE_SEAL_SHA256
[[ "${CLONE_E2E_BEFORE_EVIDENCE_SEAL_SHA256}" =~ ^[0-9a-f]{64}$ ]] ||
  dbops_die "CLONE_E2E_BEFORE_EVIDENCE_SEAL_SHA256 must be lowercase 64-hex"
[[ "${CLONE_EVIDENCE_SEAL_SHA256}" =~ ^[0-9a-f]{64}$ ]] ||
  dbops_die "CLONE_EVIDENCE_SEAL_SHA256 must be lowercase 64-hex"
[[ -d "${E2E_BEFORE_EVIDENCE_DIR}" && ! -L "${E2E_BEFORE_EVIDENCE_DIR}" ]] ||
  dbops_die "E2E_BEFORE_EVIDENCE_DIR must be an existing non-symlink directory"

dbops_verify_sentinel
EVIDENCE_DIR="${E2E_BEFORE_EVIDENCE_DIR}" EVIDENCE_STAGE=sanitized-e2e-before \
  bash "${SCRIPT_DIR}/verify-evidence-seal.sh"
provenance_report="${E2E_BEFORE_EVIDENCE_DIR}/sanitized-e2e-synthetic-provenance.tsv"
provenance_checksum="${provenance_report}.sha256"
[[ -f "$provenance_report" && ! -L "$provenance_report" \
    && -f "$provenance_checksum" && ! -L "$provenance_checksum" ]] ||
  dbops_die "sealed before-E2E evidence is missing synthetic provenance proof"
(
  cd "${E2E_BEFORE_EVIDENCE_DIR}"
  sha256sum --check "$(basename "$provenance_checksum")" >/dev/null
) || dbops_die "synthetic provenance proof checksum is invalid"
provenance_violations="$(awk -F '\t' '{ total += $2 } END { print total + 0 }' \
  "$provenance_report")"
[[ "$provenance_violations" == "0" ]] ||
  dbops_die "sealed synthetic provenance proof contains ${provenance_violations} violations"
actual_seal_sha256="$(sha256sum \
  "${E2E_BEFORE_EVIDENCE_DIR}/EVIDENCE-SEAL" | awk '{print $1}')"
[[ "$actual_seal_sha256" == "${CLONE_E2E_BEFORE_EVIDENCE_SEAL_SHA256}" ]] ||
  dbops_die "before-E2E evidence directory does not match the approved seal SHA-256"

state="$(PGOPTIONS='-c default_transaction_read_only=on -c statement_timeout=10000 -c lock_timeout=3000' \
  dbops_psql --quiet --tuples-only --no-align \
    --set=sentinel_id="${CLONE_SENTINEL_ID}" \
    --set=source_snapshot_id="${SOURCE_SNAPSHOT_ID}" \
    --set=release_id="${RELEASE_ID}" \
    --set=migration_seal_sha256="${CLONE_EVIDENCE_SEAL_SHA256}" \
    --set=before_seal_sha256="${CLONE_E2E_BEFORE_EVIDENCE_SEAL_SHA256}" <<'SQL'
SELECT CASE
  WHEN baseline_completed_at IS NULL THEN 'migration-not-complete'
  WHEN evidence_seal_sha256 <> :'migration_seal_sha256' THEN 'migration-seal-mismatch'
  WHEN e2e_before_recorded_at IS NULL THEN 'before-attestation-not-recorded'
  WHEN e2e_before_evidence_seal_sha256 <> :'before_seal_sha256' THEN 'before-seal-mismatch'
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
)" || dbops_die "before-E2E attestation query failed"

[[ "$state" == "ready" ]] ||
  dbops_die "before-E2E evidence is not release/sentinel-bound (${state:-missing})"

printf 'Sanitized before-E2E evidence attestation passed for release %s. No data was changed.\n' \
  "${RELEASE_ID}"
