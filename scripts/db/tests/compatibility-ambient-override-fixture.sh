#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DB_SCRIPT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
fixture_root="$(mktemp -d)"

run_refusal() {
  local name="$1" value="$2"
  local evidence_dir="${fixture_root}/${name}"
  local output status
  set +e
  output="$(env -i PATH="${PATH}" \
    PGHOST=clone.example.test PGPORT=5432 PGDATABASE=viralground_release_exact \
    PGUSER=clone_migrator PGPASSWORD=synthetic-not-used PGSSLMODE=verify-full \
    CLONE_KIND=exact CLONE_SENTINEL_ID=compatibility-ambient-refusal \
    SOURCE_SNAPSHOT_ID=ci-provider-snapshot \
    CLONE_ALLOWED_HOSTS=clone.example.test \
    CLONE_ALLOWED_DATABASES=viralground_release_exact \
    PRODUCTION_DB_HOST=production.example.test PRODUCTION_DB_NAME=viralground_prod \
    RELEASE_ID=ci-compatibility-ambient-refusal \
    DBOPS_CONFIRMATION=I_ACKNOWLEDGE_THIS_IS_A_DISPOSABLE_CLONE \
    RC_JAR=missing-rc.jar RC_JAR_SHA256=deadbeef \
    LEGACY_JAR=missing-legacy.jar LEGACY_JAR_SHA256=deadbeef \
    COMPAT_PGUSER=compatibility_readonly COMPAT_PGPASSWORD=synthetic-not-used \
    COMPAT_LOGIN_EMAIL=compat@example.invalid \
    COMPAT_LOGIN_EMAIL_SHA256=012e646225fb76e0536479294d09db94d0213a67bd2e4c67882b658981686215 \
    COMPAT_LOGIN_PASSWORD=synthetic-password-not-used \
    COMPAT_LOGIN_MEMBER_ID=1 \
    COMPAT_SUCCESS_LOGIN_CONFIRMATION=USE_APPROVED_COMPATIBILITY_ACCOUNT_WITH_TEMP_ONLY_AUTH_WRITES \
    GIT_COMMIT_SHA=69c32fd BUILD_TIME=2026-08-22T01:00:00Z \
    MIGRATION_EVIDENCE_DIR="${fixture_root}/missing-migration-evidence" \
    EVIDENCE_DIR="${evidence_dir}" \
    "${name}=${value}" \
    bash "${DB_SCRIPT_DIR}/verify-exact-backend-compatibility.sh" 2>&1)"
  status=$?
  set -e
  [[ "$status" == "64" ]] || {
    printf '%s override was not refused\n' "$name" >&2
    exit 1
  }
  grep -Fq "REFUSED: ambient ${name} override is forbidden" <<<"$output"
  [[ ! -e "$evidence_dir" ]] || {
    printf '%s refusal created evidence before target verification\n' "$name" >&2
    exit 1
  }
}

run_refusal DATABASE_URL 'jdbc:postgresql://redirect.example.test/other'
run_refusal SPRING_DATASOURCE_URL 'jdbc:postgresql://redirect.example.test/other'
run_refusal SPRING_APPLICATION_JSON \
  '{"spring":{"datasource":{"url":"jdbc:postgresql://redirect.example.test/other"}}}'

printf 'Compatibility ambient datasource override refusal passed.\n'
