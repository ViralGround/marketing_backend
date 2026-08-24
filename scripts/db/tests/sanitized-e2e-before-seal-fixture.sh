#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DB_SCRIPT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
fixture_root="$(mktemp -d)"
before_root="${fixture_root}/e2e-before"
fake_bin="${fixture_root}/bin"
cleanup() {
  [[ "$fixture_root" == /tmp/tmp.* ]] || return 0
  chmod -R u+w "$fixture_root" 2>/dev/null || true
  rm -rf -- "$fixture_root"
}
trap cleanup EXIT
mkdir -p "$before_root" "$fake_bin"
printf 'immutable non-synthetic fingerprint\n' >"${before_root}/sanitized-e2e-before.tsv"
printf 'synthetic_provenance\t0\n' \
  >"${before_root}/sanitized-e2e-synthetic-provenance.tsv"
(
  cd "$before_root"
  sha256sum sanitized-e2e-synthetic-provenance.tsv \
    >sanitized-e2e-synthetic-provenance.tsv.sha256
)

SOURCE_SNAPSHOT_ID=ci-provider-snapshot RELEASE_ID=ci-e2e-before EVIDENCE_DIR="$before_root" \
  EVIDENCE_STAGE=sanitized-e2e-before \
  bash "${DB_SCRIPT_DIR}/seal-evidence.sh" >/dev/null
before_seal_sha256="$(sha256sum "${before_root}/EVIDENCE-SEAL" | awk '{print $1}')"
[[ "$before_seal_sha256" =~ ^[0-9a-f]{64}$ ]]

cp "${SCRIPT_DIR}/psql-sanitized-e2e-attestation-fixture" "${fake_bin}/psql"
chmod 700 "${fake_bin}/psql"

common_env=(
  PATH="${fake_bin}:${PATH}"
  PGHOST=127.0.0.1
  PGPORT=5432
  PGDATABASE=viralground_ci_staging
  PGUSER=ci_evidence_reader
  PGSSLMODE=verify-full
  CLONE_KIND=sanitized
  CLONE_SENTINEL_ID=ci-e2e-before-sentinel
  SOURCE_SNAPSHOT_ID=ci-provider-snapshot
  CLONE_ALLOWED_HOSTS=127.0.0.1
  CLONE_ALLOWED_DATABASES=viralground_ci_staging
  PRODUCTION_DB_HOST=production.example.test
  PRODUCTION_DB_NAME=viralground_prod
  DBOPS_CONFIRMATION=I_ACKNOWLEDGE_THIS_IS_A_DISPOSABLE_CLONE
  RELEASE_ID=ci-e2e-before
  CLONE_EVIDENCE_SEAL_SHA256=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
  E2E_BEFORE_EVIDENCE_DIR="$before_root"
)

env "${common_env[@]}" \
  CLONE_E2E_BEFORE_EVIDENCE_SEAL_SHA256="$before_seal_sha256" \
  bash "${DB_SCRIPT_DIR}/assert-sanitized-e2e-before-attestation.sh" >/dev/null

legacy_root="${fixture_root}/legacy-before-without-provenance"
mkdir -p "$legacy_root"
printf 'legacy unqualified fingerprint\n' >"${legacy_root}/sanitized-e2e-before.tsv"
SOURCE_SNAPSHOT_ID=ci-provider-snapshot RELEASE_ID=ci-e2e-before EVIDENCE_DIR="$legacy_root" \
  EVIDENCE_STAGE=sanitized-e2e-before \
  bash "${DB_SCRIPT_DIR}/seal-evidence.sh" >/dev/null
legacy_seal_sha256="$(sha256sum "${legacy_root}/EVIDENCE-SEAL" | awk '{print $1}')"
set +e
output="$(env "${common_env[@]}" \
  E2E_BEFORE_EVIDENCE_DIR="$legacy_root" \
  CLONE_E2E_BEFORE_EVIDENCE_SEAL_SHA256="$legacy_seal_sha256" \
  bash "${DB_SCRIPT_DIR}/assert-sanitized-e2e-before-attestation.sh" 2>&1)"
status=$?
set -e
[[ "$status" == "64" ]]
grep -Fq 'missing synthetic provenance proof' <<<"$output"

violating_root="${fixture_root}/before-with-provenance-violation"
mkdir -p "$violating_root"
printf 'unqualified source fingerprint\n' >"${violating_root}/sanitized-e2e-before.tsv"
printf 'missing_member_allowlist_row\t1\n' \
  >"${violating_root}/sanitized-e2e-synthetic-provenance.tsv"
(
  cd "$violating_root"
  sha256sum sanitized-e2e-synthetic-provenance.tsv \
    >sanitized-e2e-synthetic-provenance.tsv.sha256
)
SOURCE_SNAPSHOT_ID=ci-provider-snapshot RELEASE_ID=ci-e2e-before EVIDENCE_DIR="$violating_root" \
  EVIDENCE_STAGE=sanitized-e2e-before \
  bash "${DB_SCRIPT_DIR}/seal-evidence.sh" >/dev/null
violating_seal_sha256="$(sha256sum "${violating_root}/EVIDENCE-SEAL" | awk '{print $1}')"
set +e
output="$(env "${common_env[@]}" \
  E2E_BEFORE_EVIDENCE_DIR="$violating_root" \
  CLONE_E2E_BEFORE_EVIDENCE_SEAL_SHA256="$violating_seal_sha256" \
  bash "${DB_SCRIPT_DIR}/assert-sanitized-e2e-before-attestation.sh" 2>&1)"
status=$?
set -e
[[ "$status" == "64" ]]
grep -Fq 'sealed synthetic provenance proof contains 1 violations' <<<"$output"

wrong_hash=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
set +e
output="$(env "${common_env[@]}" \
  CLONE_E2E_BEFORE_EVIDENCE_SEAL_SHA256="$wrong_hash" \
  bash "${DB_SCRIPT_DIR}/assert-sanitized-e2e-before-attestation.sh" 2>&1)"
status=$?
set -e
[[ "$status" == "64" ]]
grep -Fq 'does not match the approved seal SHA-256' <<<"$output"

set +e
output="$(env "${common_env[@]}" \
  CLONE_E2E_BEFORE_EVIDENCE_SEAL_SHA256="${before_seal_sha256^^}" \
  bash "${DB_SCRIPT_DIR}/assert-sanitized-e2e-before-attestation.sh" 2>&1)"
status=$?
set -e
[[ "$status" == "64" ]]
grep -Fq 'must be lowercase 64-hex' <<<"$output"

printf 'Sanitized before-E2E seal binding and lowercase-hash refusal passed.\n'
