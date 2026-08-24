#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DB_SCRIPT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
GUARD="${DB_SCRIPT_DIR}/lib/clone_guard.sh"
fixture_root="$(mktemp -d)"
fake_bin="${fixture_root}/bin"
mkdir -p "$fake_bin"
cp "${SCRIPT_DIR}/psql-source-snapshot-fixture" "${fake_bin}/psql"
chmod 700 "${fake_bin}/psql"

cleanup() {
  [[ "$fixture_root" == /tmp/tmp.* ]] || return 0
  chmod -R u+w "$fixture_root" 2>/dev/null || true
  rm -rf -- "$fixture_root"
}
trap cleanup EXIT

common_env=(
  PATH="${fake_bin}:${PATH}"
  EXPECTED_SOURCE_SNAPSHOT_ID=provider-snapshot-approved
  PGHOST=clone.example.test
  PGPORT=5432
  PGDATABASE=viralground_release_exact
  PGUSER=clone_reader
  PGSSLMODE=verify-full
  CLONE_KIND=exact
  CLONE_SENTINEL_ID=ci-source-snapshot-sentinel
  CLONE_ALLOWED_HOSTS=clone.example.test
  CLONE_ALLOWED_DATABASES=viralground_release_exact
  PRODUCTION_DB_HOST=production.example.test
  PRODUCTION_DB_NAME=viralground_prod
  DBOPS_CONFIRMATION=I_ACKNOWLEDGE_THIS_IS_A_DISPOSABLE_CLONE
  RELEASE_ID=ci-source-snapshot
)

set +e
output="$(env "${common_env[@]}" bash -c 'source "$1"; dbops_verify_sentinel' _ "$GUARD" 2>&1)"
status=$?
set -e
[[ "$status" == "64" ]]
grep -Fq 'required environment variable SOURCE_SNAPSHOT_ID is empty' <<<"$output"

set +e
output="$(env "${common_env[@]}" SOURCE_SNAPSHOT_ID=provider-snapshot-wrong \
  bash -c 'source "$1"; dbops_verify_sentinel' _ "$GUARD" 2>&1)"
status=$?
set -e
[[ "$status" == "64" ]]
grep -Fq 'sentinel validation returned missing-or-expired-sentinel' <<<"$output"

env "${common_env[@]}" SOURCE_SNAPSHOT_ID=provider-snapshot-approved \
  bash -c 'source "$1"; dbops_verify_sentinel' _ "$GUARD"

sanitized_env=(
  "${common_env[@]}"
  PGDATABASE=viralground_release_staging
  CLONE_KIND=sanitized
  CLONE_ALLOWED_DATABASES=viralground_release_staging
)
env "${sanitized_env[@]}" SOURCE_SNAPSHOT_ID=provider-snapshot-approved \
  bash -c 'source "$1"; dbops_verify_sentinel' _ "$GUARD"

set +e
output="$(env "${sanitized_env[@]}" SOURCE_SNAPSHOT_ID=provider-snapshot-wrong \
  bash -c 'source "$1"; dbops_verify_sentinel' _ "$GUARD" 2>&1)"
status=$?
set -e
[[ "$status" == "64" ]]
grep -Fq 'sentinel validation returned missing-or-expired-sentinel' <<<"$output"

seal_root="${fixture_root}/sealed-exact"
mkdir -p "$seal_root"
printf 'snapshot-bound evidence\n' >"${seal_root}/artifact.tsv"
SOURCE_SNAPSHOT_ID=provider-snapshot-approved RELEASE_ID=ci-source-snapshot \
  EVIDENCE_DIR="$seal_root" EVIDENCE_STAGE=exact-fixture \
  bash "${DB_SCRIPT_DIR}/seal-evidence.sh" >/dev/null
SOURCE_SNAPSHOT_ID=provider-snapshot-approved RELEASE_ID=ci-source-snapshot \
  EVIDENCE_DIR="$seal_root" EVIDENCE_STAGE=exact-fixture \
  bash "${DB_SCRIPT_DIR}/verify-evidence-seal.sh" >/dev/null

set +e
output="$(SOURCE_SNAPSHOT_ID=provider-snapshot-wrong RELEASE_ID=ci-source-snapshot \
  EVIDENCE_DIR="$seal_root" EVIDENCE_STAGE=exact-fixture \
  bash "${DB_SCRIPT_DIR}/verify-evidence-seal.sh" 2>&1)"
status=$?
set -e
[[ "$status" == "64" ]]
grep -Fq 'sealed evidence source snapshot mismatch' <<<"$output"

sanitized_seal_root="${fixture_root}/sealed-sanitized"
mkdir -p "$sanitized_seal_root"
printf 'snapshot-bound sanitized evidence\n' >"${sanitized_seal_root}/artifact.tsv"
SOURCE_SNAPSHOT_ID=provider-snapshot-approved RELEASE_ID=ci-source-snapshot \
  EVIDENCE_DIR="$sanitized_seal_root" EVIDENCE_STAGE=sanitized-fixture \
  bash "${DB_SCRIPT_DIR}/seal-evidence.sh" >/dev/null
SOURCE_SNAPSHOT_ID=provider-snapshot-approved RELEASE_ID=ci-source-snapshot \
  EVIDENCE_DIR="$sanitized_seal_root" EVIDENCE_STAGE=sanitized-fixture \
  bash "${DB_SCRIPT_DIR}/verify-evidence-seal.sh" >/dev/null

exact_source_hash="$(sed -n 's/^sourceSnapshotIdSha256=//p' "${seal_root}/EVIDENCE-SEAL")"
sanitized_source_hash="$(sed -n 's/^sourceSnapshotIdSha256=//p' \
  "${sanitized_seal_root}/EVIDENCE-SEAL")"
[[ "$exact_source_hash" =~ ^[0-9a-f]{64}$ ]]
[[ "$exact_source_hash" == "$sanitized_source_hash" ]]

mismatched_sanitized_root="${fixture_root}/sealed-sanitized-mismatch"
mkdir -p "$mismatched_sanitized_root"
printf 'wrong-source sanitized evidence\n' >"${mismatched_sanitized_root}/artifact.tsv"
SOURCE_SNAPSHOT_ID=provider-snapshot-wrong RELEASE_ID=ci-source-snapshot \
  EVIDENCE_DIR="$mismatched_sanitized_root" EVIDENCE_STAGE=sanitized-fixture \
  bash "${DB_SCRIPT_DIR}/seal-evidence.sh" >/dev/null
set +e
output="$(SOURCE_SNAPSHOT_ID=provider-snapshot-approved RELEASE_ID=ci-source-snapshot \
  EVIDENCE_DIR="$mismatched_sanitized_root" EVIDENCE_STAGE=sanitized-fixture \
  bash "${DB_SCRIPT_DIR}/verify-evidence-seal.sh" 2>&1)"
status=$?
set -e
[[ "$status" == "64" ]]
grep -Fq 'sealed evidence source snapshot mismatch' <<<"$output"

printf 'Exact/sanitized source snapshot sentinel and sealed-evidence binding refusal passed.\n'
