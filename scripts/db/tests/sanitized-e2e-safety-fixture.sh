#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DB_SCRIPT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
fixture_root="$(mktemp -d)"
fake_bin="${fixture_root}/bin"
mkdir -p "$fake_bin"
cp "${SCRIPT_DIR}/psql-sanitized-e2e-safety-fixture" "${fake_bin}/psql"
chmod 700 "${fake_bin}/psql"

cleanup() {
  [[ "$fixture_root" == /tmp/tmp.* ]] || return 0
  chmod -R u+w "$fixture_root" 2>/dev/null || true
  rm -rf -- "$fixture_root"
}
trap cleanup EXIT

common_env=(
  PATH="${fake_bin}:${PATH}"
  PGHOST=127.0.0.1
  PGPORT=5432
  PGDATABASE=viralground_ci_staging
  PGUSER=ci_evidence_reader
  PGPASSWORD=synthetic-not-used
  PGSSLMODE=verify-full
  CLONE_KIND=sanitized
  CLONE_SENTINEL_ID=ci-e2e-safety-sentinel
  SOURCE_SNAPSHOT_ID=ci-provider-snapshot
  CLONE_ALLOWED_HOSTS=127.0.0.1
  CLONE_ALLOWED_DATABASES=viralground_ci_staging
  PRODUCTION_DB_HOST=production.example.test
  PRODUCTION_DB_NAME=viralground_prod
  DBOPS_CONFIRMATION=I_ACKNOWLEDGE_THIS_IS_A_DISPOSABLE_CLONE
  RELEASE_ID=ci-e2e-safety
  CLONE_EVIDENCE_SEAL_SHA256=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
  SYNTHETIC_MEMBER_IDS=101,102,103
  SYNTHETIC_CONTACT_IDS=
  E2E_ATTESTATION_PGUSER=ci_e2e_attestor
  E2E_ATTESTATION_PGPASSWORD=synthetic-attestor-not-used
  E2E_BEFORE_ATTESTATION_CONFIRMATION=ATTEST_IMMUTABLE_SANITIZED_E2E_BEFORE_ONCE
  E2E_EVIDENCE_PHASE=before
  PUBLIC_SCHEMA_ALLOWLIST_FIXTURE_FILE="${DB_SCRIPT_DIR}/public-schema-allowlist.tsv"
)

success_root="${fixture_root}/success"
env "${common_env[@]}" SANITIZED_E2E_FIXTURE_MODE=success \
  EVIDENCE_DIR="$success_root" \
  bash "${DB_SCRIPT_DIR}/sanitized-e2e-evidence.sh" >/dev/null
[[ -f "${success_root}/sanitized-e2e-synthetic-provenance.tsv" ]]
[[ -f "${success_root}/EVIDENCE-SEAL" ]]

run_refusal() {
  local mode="$1" expected_check="$2"
  local evidence_root="${fixture_root}/${mode}"
  local output status
  set +e
  output="$(env "${common_env[@]}" SANITIZED_E2E_FIXTURE_MODE="$mode" \
    EVIDENCE_DIR="$evidence_root" \
    bash "${DB_SCRIPT_DIR}/sanitized-e2e-evidence.sh" 2>&1)"
  status=$?
  set -e
  [[ "$status" == "64" ]]
  if [[ "$mode" == "provenance" ]]; then
    grep -Fq 'synthetic allowlist provenance has 1 violations' <<<"$output"
    grep -Fq $'missing_member_allowlist_row\t1' \
      "${evidence_root}/sanitized-e2e-synthetic-provenance.tsv"
  else
    grep -Fq 'E2E evidence attestor role safety gate found 1 violations' <<<"$output"
    grep -Fq "${expected_check}"$'\t1' \
      "${evidence_root}/e2e-attestor-role-safety.tsv"
  fi
}

run_refusal provenance missing_member_allowlist_row
run_refusal table application_table_privilege
run_refusal column application_column_privilege
run_refusal sequence application_sequence_privilege
run_refusal security-definer executable_security_definer_path

printf 'Sanitized E2E provenance and attestor privilege refusal fixtures passed.\n'
