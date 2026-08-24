#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DB_SCRIPT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
fixture_root="$(mktemp -d)"
fake_bin="${fixture_root}/bin"
mkdir -p "$fake_bin"
cp "${SCRIPT_DIR}/psql-public-schema-allowlist-fixture" "${fake_bin}/psql"
chmod 700 "${fake_bin}/psql"

cleanup() {
  [[ "$fixture_root" == /tmp/tmp.* ]] || return 0
  rm -rf -- "$fixture_root"
}
trap cleanup EXIT

common_env=(
  PATH="${fake_bin}:${PATH}"
  PGHOST=127.0.0.1
  PGPORT=5432
  PGDATABASE=viralground_ci_staging
  PGUSER=clone_migrator
  PGPASSWORD=synthetic-not-used
  PGSSLMODE=verify-full
  CLONE_KIND=sanitized
  CLONE_SENTINEL_ID=ci-schema-allowlist-sentinel
  SOURCE_SNAPSHOT_ID=ci-provider-snapshot
  CLONE_ALLOWED_HOSTS=127.0.0.1
  CLONE_ALLOWED_DATABASES=viralground_ci_staging
  PRODUCTION_DB_HOST=production.example.test
  PRODUCTION_DB_NAME=viralground_prod
  DBOPS_CONFIRMATION=I_ACKNOWLEDGE_THIS_IS_A_DISPOSABLE_CLONE
  RELEASE_ID=ci-schema-allowlist
  SANITIZE_CONFIRMATION=ERASE_PII_ON_DISPOSABLE_SANITIZED_CLONE
  PUBLIC_SCHEMA_ALLOWLIST_MODE=legacy-subset
)

run_unknown_refusal() {
  local mode="$1"
  local evidence_root="${fixture_root}/${mode}"
  local output status
  set +e
  output="$(env "${common_env[@]}" PUBLIC_SCHEMA_FIXTURE_MODE="$mode" \
    EVIDENCE_DIR="$evidence_root" \
    bash "${DB_SCRIPT_DIR}/sanitize-clone.sh" 2>&1)"
  status=$?
  set -e
  [[ "$status" == "64" ]]
  grep -Fq 'unknown table/column entries; sanitization is forbidden' <<<"$output"
  case "$mode" in
    unknown_column)
      grep -Fq $'unknown_public_table\t0' \
        "${evidence_root}/public-schema-allowlist-check-legacy-subset.tsv"
      grep -Fq $'unknown_public_column\t1' \
        "${evidence_root}/public-schema-allowlist-check-legacy-subset.tsv"
      ;;
    unknown_table)
      grep -Fq $'unknown_public_table\t1' \
        "${evidence_root}/public-schema-allowlist-check-legacy-subset.tsv"
      grep -Fq $'unknown_public_column\t1' \
        "${evidence_root}/public-schema-allowlist-check-legacy-subset.tsv"
      ;;
    zero_column_table)
      grep -Fq $'unknown_public_table\t1' \
        "${evidence_root}/public-schema-allowlist-check-legacy-subset.tsv"
      grep -Fq $'unknown_public_column\t0' \
        "${evidence_root}/public-schema-allowlist-check-legacy-subset.tsv"
      ;;
  esac
  (
    cd "$evidence_root"
    sha256sum --check public-schema-allowlist.sha256 >/dev/null
    sha256sum --check public-table-inventory-legacy-subset.tsv.sha256 >/dev/null
    sha256sum --check public-schema-inventory-legacy-subset.tsv.sha256 >/dev/null
    sha256sum --check public-schema-allowlist-check-legacy-subset.tsv.sha256 >/dev/null
  )
}

run_unknown_refusal unknown_column
run_unknown_refusal unknown_table
run_unknown_refusal zero_column_table

run_object_refusal() {
  local mode="$1" expected_check="$2"
  local evidence_root="${fixture_root}/${mode}"
  local output status
  set +e
  output="$(env "${common_env[@]}" PUBLIC_SCHEMA_FIXTURE_MODE="$mode" \
    EVIDENCE_DIR="$evidence_root" \
    bash "${DB_SCRIPT_DIR}/sanitize-clone.sh" 2>&1)"
  status=$?
  set -e
  [[ "$status" == "64" ]]
  grep -Fq 'database object safety boundary found 1 violations' <<<"$output"
  grep -Fq "${expected_check}"$'\t1' \
    "${evidence_root}/database-object-safety-legacy-subset.tsv"
}

run_object_refusal foreign_relation nonlocal_public_application_relation
run_object_refusal nonpublic_schema unexpected_non_system_schema
run_object_refusal unexpected_trigger unexpected_public_user_trigger
run_object_refusal unexpected_routine unexpected_public_user_routine
run_object_refusal tampered_routine unexpected_public_user_routine
run_object_refusal nonpublic_trigger_routine unexpected_public_user_trigger
printf 'Unknown restored public table/column refusal passed before sanitization mutation.\n'
