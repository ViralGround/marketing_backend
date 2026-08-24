#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/clone_guard.sh
source "${SCRIPT_DIR}/lib/clone_guard.sh"

dbops_verify_sentinel
dbops_create_fresh_evidence_dir
case "${CLONE_KIND}" in
  exact)
    required_migration_confirmation="BASELINE_V1_ON_DISPOSABLE_EXACT_CLONE_ONCE"
    ;;
  sanitized)
    required_migration_confirmation="BASELINE_V1_ON_DISPOSABLE_SANITIZED_CLONE_ONCE"
    ;;
  *)
    dbops_die "migration runner requires CLONE_KIND=exact or CLONE_KIND=sanitized"
    ;;
esac
[[ "${MIGRATION_CONFIRMATION:-}" == "${required_migration_confirmation}" ]] ||
  dbops_die "MIGRATION_CONFIRMATION does not match the required phrase"

# The environment is permanently baseline-disabled. The sole baseline-enabled
# process below receives an explicit command-line argument only after the
# database-side one-shot sentinel transition succeeds.
[[ "${FLYWAY_BASELINE_ON_MIGRATE:-false}" == "false" ]] ||
  dbops_die "FLYWAY_BASELINE_ON_MIGRATE must be false before the guarded run"
[[ "${SPRING_FLYWAY_BASELINE_ON_MIGRATE:-false}" == "false" ]] ||
  dbops_die "SPRING_FLYWAY_BASELINE_ON_MIGRATE must be false before the guarded run"
export FLYWAY_BASELINE_ON_MIGRATE=false
export SPRING_FLYWAY_BASELINE_ON_MIGRATE=false

dbops_require RC_JAR
dbops_require RC_JAR_SHA256
dbops_require PGPASSWORD
dbops_require RELEASE_ID
dbops_require GIT_COMMIT_SHA
dbops_require BUILD_TIME
[[ -f "${RC_JAR}" ]] || dbops_die "RC_JAR does not exist"
command -v java >/dev/null 2>&1 || dbops_die "Java 21 is required"
command -v timeout >/dev/null 2>&1 || dbops_die "GNU timeout is required"
command -v sha256sum >/dev/null 2>&1 || dbops_die "sha256sum is required"

actual_jar_sha="$(sha256sum "${RC_JAR}" | awk '{print $1}')"
[[ "${actual_jar_sha}" == "${RC_JAR_SHA256,,}" ]] || dbops_die "release jar SHA-256 does not match"
java_major="$(java -version 2>&1 | sed -nE '1s/.*version "([0-9]+).*/\1/p')"
[[ "${java_major}" == "21" ]] || dbops_die "migration runner requires Java 21"

dbops_require APP_ENV
[[ "${APP_ENV}" == "preproduction" ]] || dbops_die "APP_ENV must be preproduction"

dbops_assert_false() {
  local name="$1"
  [[ "${!name:-}" == "false" ]] || dbops_die "${name} must be explicitly false"
}

for flag in \
  APP_SCHEDULING_ENABLED INSTAGRAM_SYNC_ENABLED \
  INSTAGRAM_OAUTH_STATE_CLEANUP_ENABLED INSTAGRAM_WEBHOOK_CLEANUP_ENABLED \
  NOTIFICATION_OUTBOX_DISPATCH_ENABLED FEATURE_PAYMENTS_ENABLED \
  FEATURE_INSTAGRAM_ENABLED FEATURE_UPLOADS_ENABLED DEMO_BOOTSTRAP_ENABLED; do
  dbops_assert_false "$flag"
done

[[ "${FILES_STORAGE:-}" == "disabled" ]] || dbops_die "FILES_STORAGE must be disabled"
[[ "${EMAIL_DELIVERY_MODE:-}" == "disabled" ]] || dbops_die "EMAIL_DELIVERY_MODE must be disabled"
[[ "${PAYMENTS_GATEWAY:-}" == "disabled" ]] || dbops_die "PAYMENTS_GATEWAY must be disabled"
[[ -z "${ADMIN_BOOTSTRAP_EMAIL:-}" && -z "${ADMIN_BOOTSTRAP_PASSWORD:-}" ]] ||
  dbops_die "admin bootstrap must be empty"

timeout_seconds="${DBOPS_JAVA_TIMEOUT_SECONDS:-300}"
[[ "$timeout_seconds" =~ ^[0-9]+$ ]] || dbops_die "DBOPS_JAVA_TIMEOUT_SECONDS must be numeric"
(( timeout_seconds >= 30 && timeout_seconds <= 1800 )) ||
  dbops_die "DBOPS_JAVA_TIMEOUT_SECONDS must be between 30 and 1800"

database_url="jdbc:postgresql://${PGHOST}:${PGPORT}/${PGDATABASE}?sslmode=${PGSSLMODE}&currentSchema=public"
export DATABASE_URL="$database_url"
export SPRING_DATASOURCE_USERNAME="${PGUSER}"
export SPRING_DATASOURCE_PASSWORD="${PGPASSWORD}"

# Both clone kinds capture this before any sanitizer DML. Qualification compares
# the sealed files byte-for-byte in addition to provider-native restore receipts.
bash "${SCRIPT_DIR}/capture-source-origin-fingerprint.sh"

# A sanitized clone is scrubbed and verified while it still has the legacy
# shape. No application or migration process is allowed to start first.
if [[ "${CLONE_KIND}" == "sanitized" ]]; then
  current_step="legacy-sanitization"
  bash "${SCRIPT_DIR}/sanitize-clone.sh"
  bash "${SCRIPT_DIR}/verify-legacy-sanitization.sh"
fi

# These guarded scripts force their own read-only sessions.
bash "${SCRIPT_DIR}/preflight-readonly.sh"
EVIDENCE_PHASE=before bash "${SCRIPT_DIR}/capture-schema-evidence.sh"

history_state="$(dbops_psql --quiet --tuples-only --no-align <<'SQL'
SELECT CASE WHEN to_regclass('public.flyway_schema_history') IS NULL
            THEN 'absent' ELSE 'present' END;
SQL
)"
[[ "$history_state" == "absent" ]] ||
  dbops_die "flyway_schema_history already exists; one-time baseline runner will not continue"

current_step="baseline-v1"
failure_file="${EVIDENCE_DIR}/migration-failed.txt"
runner_completed=false
evidence_sealed=false
on_runner_exit() {
  local status=$?
  trap - EXIT
  if [[ "$runner_completed" != "true" ]]; then
    if [[ "$evidence_sealed" == "true" || -e "${EVIDENCE_DIR}/EVIDENCE-SEAL" \
        || -e "${EVIDENCE_DIR}/EVIDENCE-MANIFEST.sha256" ]]; then
      printf 'FAILED after evidence sealing at step=%s exitCode=%s; evidence was not mutated. Destroy and restore the clone.\n' \
        "$current_step" "$status" >&2
    else
      printf 'status=FAILED\nstep=%s\nexitCode=%s\nfailedAtUtc=%s\n' \
        "$current_step" "$status" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" >"$failure_file"
    fi
  fi
  exit "$status"
}
trap on_runner_exit EXIT

cat >"${EVIDENCE_DIR}/migration-run.txt" <<EOF
cloneKind=${CLONE_KIND}
sourceSnapshotIdSha256=$(printf '%s' "${SOURCE_SNAPSHOT_ID}" | sha256sum | awk '{print $1}')
releaseId=${RELEASE_ID}
gitCommitSha=${GIT_COMMIT_SHA}
buildTime=${BUILD_TIME}
jarSha256=${actual_jar_sha}
startedAtUtc=$(date -u +%Y-%m-%dT%H:%M:%SZ)
EOF

run_candidate_validation() {
  local baseline_on_migrate="$1"
  timeout --signal=TERM --kill-after=20s "${timeout_seconds}s" \
    java -jar "${RC_JAR}" \
      --spring.main.web-application-type=none \
      --spring.datasource.url="${database_url}" \
      --app.environment=preproduction \
      --app.release-id="${RELEASE_ID}" \
      --app.git-commit-sha="${GIT_COMMIT_SHA}" \
      --app.build-time="${BUILD_TIME}" \
      --app.migration-runner.enabled=true \
      --app.migration-runner.clone-kind="${CLONE_KIND}" \
      --app.migration-runner.sentinel-id="${CLONE_SENTINEL_ID}" \
      --app.migration-runner.source-snapshot-id="${SOURCE_SNAPSHOT_ID}" \
      --app.migration-runner.allowed-hosts="${CLONE_ALLOWED_HOSTS}" \
      --app.migration-runner.allowed-databases="${CLONE_ALLOWED_DATABASES}" \
      --app.migration-runner.production-host="${PRODUCTION_DB_HOST}" \
      --app.migration-runner.production-database="${PRODUCTION_DB_NAME}" \
      --app.migration-runner.database-confirmation="${DBOPS_CONFIRMATION}" \
      --app.migration-runner.migration-confirmation="${MIGRATION_CONFIRMATION}" \
      --spring.task.scheduling.enabled=false \
      --spring.flyway.enabled=true \
      --spring.flyway.baseline-version=1 \
      --spring.flyway.baseline-on-migrate="${baseline_on_migrate}" \
      --spring.flyway.validate-on-migrate=true \
      --spring.jpa.hibernate.ddl-auto=validate \
      --jwt.secret=guarded-migration-runner-no-authentication-secret-32-bytes \
      --app.url=http://127.0.0.1 \
      --cors.allowed-origins=http://127.0.0.1 \
      --sentry.dsn= \
      --resend.api-key= \
      --rate-limit.backend=local \
      --spring.data.redis.url=redis://127.0.0.1:1 \
      --app.scheduling.enabled=false \
      --instagram.sync.enabled=false \
      --instagram.oauth-state.cleanup-enabled=false \
      --instagram.webhook.cleanup-enabled=false \
      --notification.outbox.enabled=false \
      --notification.outbox.dispatch-enabled=false \
      --features.payments.enabled=false \
      --features.instagram.enabled=false \
      --features.uploads.enabled=false \
      --instagram.provider=disabled \
      --instagram.environment=preproduction \
      --files.storage=disabled \
      --email.delivery-mode=disabled \
      --email.allowed-recipients= \
      --payments.gateway=disabled \
      --demo.bootstrap.enabled=false \
      --admin.bootstrap.email= \
      --admin.bootstrap.password=
}

# The in-process Flyway strategy repeats all target/data checks and atomically
# transitions baseline_started_at before this sole true path. Any interruption
# after that compare-and-set requires a fresh restore and a new sentinel.
run_candidate_validation true

baseline_count="$(dbops_psql --quiet --tuples-only --no-align <<'SQL'
SELECT COUNT(*)
FROM flyway_schema_history
WHERE version = '1' AND type = 'BASELINE' AND success = TRUE;
SQL
)"
[[ "$baseline_count" == "1" ]] || dbops_die "exactly one successful V1 baseline was not recorded"

current_step="baseline-false-validate-1"
run_candidate_validation false
current_step="baseline-false-validate-2"
run_candidate_validation false

# Later migrations can introduce token/outbox/file columns or backfill external
# identifiers. Scrub the migrated schema again before producing evidence or
# permitting any staging application process.
if [[ "${CLONE_KIND}" == "sanitized" ]]; then
  current_step="post-migration-sanitization"
  bash "${SCRIPT_DIR}/sanitize-clone.sh"
  bash "${SCRIPT_DIR}/verify-sanitization.sh"
fi

current_step="post-migration-evidence"
EVIDENCE_PHASE=after bash "${SCRIPT_DIR}/capture-schema-evidence.sh"
bash "${SCRIPT_DIR}/post-migration-verify.sh"
bash "${SCRIPT_DIR}/compare-evidence.sh"

current_step="evidence-seal"
printf 'readyToSealAtUtc=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  >>"${EVIDENCE_DIR}/migration-run.txt"
sha256sum "${EVIDENCE_DIR}/migration-run.txt" >"${EVIDENCE_DIR}/migration-run.sha256"
EVIDENCE_STAGE="${CLONE_KIND}-migration" \
  bash "${SCRIPT_DIR}/seal-evidence.sh"
evidence_sealed=true
evidence_seal_sha256="$(sha256sum "${EVIDENCE_DIR}/EVIDENCE-SEAL" | awk '{print $1}')"
[[ "$evidence_seal_sha256" =~ ^[0-9a-f]{64}$ ]] ||
  dbops_die "evidence seal SHA-256 is invalid"

# Completion and its release-bound evidence attestation are one database write.
# If this compare-and-set fails the already sealed evidence root stays immutable,
# and the disposable clone must be destroyed instead of retried.
current_step="sentinel-completion"
completed_seal_sha256="$(dbops_psql --quiet --tuples-only --no-align \
  --set=sentinel_id="${CLONE_SENTINEL_ID}" \
  --set=clone_kind="${CLONE_KIND}" \
  --set=source_snapshot_id="${SOURCE_SNAPSHOT_ID}" \
  --set=release_id="${RELEASE_ID}" \
  --set=evidence_seal_sha256="${evidence_seal_sha256}" <<'SQL'
UPDATE preprod_guard.clone_sentinel
SET baseline_completed_at = CURRENT_TIMESTAMP,
    evidence_seal_sha256 = :'evidence_seal_sha256'
WHERE sentinel_id = :'sentinel_id'
  AND clone_kind = :'clone_kind'
  AND source_snapshot_id = :'source_snapshot_id'
  AND release_id = :'release_id'
  AND baseline_started_at IS NOT NULL
  AND baseline_completed_at IS NULL
  AND evidence_seal_sha256 IS NULL
RETURNING evidence_seal_sha256;
SQL
)"
[[ "$completed_seal_sha256" == "$evidence_seal_sha256" ]] ||
  dbops_die "could not atomically attest the completed baseline sentinel"

runner_completed=true
trap - EXIT
printf '%s-clone baseline and two baseline-disabled validations completed; evidenceSealSha256=%s.\n' \
  "${CLONE_KIND}" "$evidence_seal_sha256"
