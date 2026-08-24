#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DB_SCRIPT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
fixture_root="$(mktemp -d)"
fake_bin="${fixture_root}/bin"
mkdir -p "$fake_bin"
cp "${SCRIPT_DIR}/psql-role-escalation-fixture" "${fake_bin}/psql"
chmod 700 "${fake_bin}/psql"

cleanup() {
  [[ "$fixture_root" == /tmp/tmp.* ]] || return 0
  chmod -R u+w "$fixture_root" 2>/dev/null || true
  rm -rf -- "$fixture_root"
}
trap cleanup EXIT

common_env=(
  PATH="${fake_bin}:${PATH}"
  PGHOST=production.example.test
  PGPORT=5432
  PGDATABASE=viralground_prod
  PGUSER=production_audit
  PGSSLMODE=verify-full
  PGPASSWORD=synthetic-not-used
  PRODUCTION_DB_HOST=production.example.test
  PRODUCTION_DB_NAME=viralground_prod
  PRODUCTION_READONLY_ROLE=production_audit
  PRODUCTION_AUDIT_CONFIRMATION=I_ACKNOWLEDGE_PRODUCTION_READ_ONLY_METADATA_AUDIT_ONLY
  RELEASE_ID=ci-production-role-fixture
)

run_privilege_refusal() {
  local mode="$1" expected_check="$2"
  local evidence_root="${fixture_root}/${mode}"
  local output status
  set +e
  output="$(env "${common_env[@]}" PSQL_FIXTURE_VIOLATION="$mode" \
    EVIDENCE_DIR="$evidence_root" \
    bash "${DB_SCRIPT_DIR}/production-readonly-audit.sh" 2>&1)"
  status=$?
  set -e
  [[ "$status" == "64" ]]
  grep -Fq "${expected_check}"$'\t1' <<<"$output"
  grep -Fq 'production role/session safety gate found 1 violations' <<<"$output"
  [[ ! -e "${evidence_root}/database-metadata.tsv" ]]
}

run_privilege_refusal set_role set_role_escalation_membership
run_privilege_refusal column_write column_write_privilege
run_privilege_refusal security_definer executable_security_definer_path
run_privilege_refusal dangerous_role dangerous_predefined_role_membership
run_privilege_refusal temp database_temp_privilege
run_privilege_refusal rls public_rls_enabled_or_forced
run_privilege_refusal rls_policy public_rls_policy_exists

existing_root="${fixture_root}/existing-root"
mkdir -p "$existing_root"
set +e
output="$(env "${common_env[@]}" EVIDENCE_DIR="$existing_root" \
  bash "${DB_SCRIPT_DIR}/production-readonly-audit.sh" 2>&1)"
status=$?
set -e
[[ "$status" == "64" ]]
grep -Fq 'production audit EVIDENCE_DIR must be a new path' <<<"$output"

printf 'Production read-only privilege/RLS and fresh evidence-root refusals passed.\n'
