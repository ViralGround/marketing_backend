#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/clone_guard.sh
source "${SCRIPT_DIR}/lib/clone_guard.sh"
# shellcheck source=lib/http_contract.sh
source "${SCRIPT_DIR}/lib/http_contract.sh"

[[ "${CLONE_KIND:-}" == "exact" ]] ||
  dbops_die "backend compatibility validation requires CLONE_KIND=exact"
for name in PGPASSWORD RC_JAR RC_JAR_SHA256 LEGACY_JAR LEGACY_JAR_SHA256 \
  COMPAT_PGUSER COMPAT_PGPASSWORD COMPAT_LOGIN_EMAIL COMPAT_LOGIN_EMAIL_SHA256 \
  COMPAT_LOGIN_PASSWORD COMPAT_LOGIN_MEMBER_ID GIT_COMMIT_SHA BUILD_TIME \
  MIGRATION_EVIDENCE_DIR; do
  dbops_require "$name"
done
[[ "${COMPAT_SUCCESS_LOGIN_CONFIRMATION:-}" == \
    "USE_APPROVED_COMPATIBILITY_ACCOUNT_WITH_TEMP_ONLY_AUTH_WRITES" ]] ||
  dbops_die "COMPAT_SUCCESS_LOGIN_CONFIRMATION does not match the required phrase"
[[ "${COMPAT_LOGIN_EMAIL}" =~ ^[A-Za-z0-9._+%-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$ ]] ||
  dbops_die "COMPAT_LOGIN_EMAIL is malformed"
[[ "${COMPAT_LOGIN_EMAIL_SHA256}" =~ ^[0-9a-f]{64}$ ]] ||
  dbops_die "COMPAT_LOGIN_EMAIL_SHA256 must be lowercase 64-hex"
command -v sha256sum >/dev/null 2>&1 || dbops_die "sha256sum is required"
actual_login_email_sha256="$(printf '%s' "${COMPAT_LOGIN_EMAIL,,}" | sha256sum | awk '{print $1}')"
[[ "$actual_login_email_sha256" == "$COMPAT_LOGIN_EMAIL_SHA256" ]] ||
  dbops_die "approved compatibility login email hash mismatch"
(( ${#COMPAT_LOGIN_PASSWORD} >= 12 )) || dbops_die "COMPAT_LOGIN_PASSWORD is too short"
[[ "${COMPAT_LOGIN_MEMBER_ID}" =~ ^[1-9][0-9]*$ ]] ||
  dbops_die "COMPAT_LOGIN_MEMBER_ID must be a positive integer"

# Spring accepts datasource/config overrides from several ambient channels. Refuse
# them before checking the target so the two archived processes cannot be redirected
# away from the exact clone that this wrapper fingerprints and proves unable to
# mutate persistent application objects.
for name in DATABASE_URL JDBC_DATABASE_URL SPRING_DATASOURCE_URL \
  SPRING_DATASOURCE_USERNAME SPRING_DATASOURCE_PASSWORD SPRING_APPLICATION_JSON \
  SPRING_CONFIG_LOCATION SPRING_CONFIG_ADDITIONAL_LOCATION SPRING_PROFILES_ACTIVE \
  JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS; do
  [[ -z "${!name:-}" ]] || dbops_die "ambient ${name} override is forbidden"
done

dbops_assert_static_target
[[ "${COMPAT_PGUSER}" =~ ^[A-Za-z0-9][A-Za-z0-9_.-]*$ ]] ||
  dbops_die "COMPAT_PGUSER must be a plain role identifier"
[[ "${COMPAT_PGUSER}" != "${PGUSER}" ]] ||
  dbops_die "COMPAT_PGUSER must be a separate persistent-read-only role, not the migration role"
dbops_verify_sentinel
[[ "${MIGRATION_EVIDENCE_DIR}" != "${EVIDENCE_DIR}" ]] ||
  dbops_die "compatibility evidence must use a fresh root separate from migration evidence"
EVIDENCE_DIR="${MIGRATION_EVIDENCE_DIR}" EVIDENCE_STAGE=exact-migration \
  bash "${SCRIPT_DIR}/verify-evidence-seal.sh"
migration_evidence_seal_sha256="$(sha256sum \
  "${MIGRATION_EVIDENCE_DIR}/EVIDENCE-SEAL" | awk '{print $1}')"
[[ "$migration_evidence_seal_sha256" =~ ^[0-9a-f]{64}$ ]] ||
  dbops_die "migration evidence seal SHA-256 is invalid"
dbops_create_fresh_evidence_dir

command -v java >/dev/null 2>&1 || dbops_die "Java 21 is required"
command -v timeout >/dev/null 2>&1 || dbops_die "GNU timeout is required"
command -v curl >/dev/null 2>&1 || dbops_die "curl is required"
python_bin="${DBOPS_PYTHON_BIN:-python3}"
command -v "$python_bin" >/dev/null 2>&1 ||
  dbops_die "Python interpreter is required (DBOPS_PYTHON_BIN=${python_bin})"
"$python_bin" --version >/dev/null 2>&1 ||
  dbops_die "configured Python interpreter is not executable: ${python_bin}"
java_major="$(java -version 2>&1 | sed -nE '1s/.*version "([0-9]+).*/\1/p')"
[[ "$java_major" == "21" ]] || dbops_die "compatibility validation requires Java 21"

timeout_seconds="${DBOPS_COMPATIBILITY_TIMEOUT_SECONDS:-180}"
[[ "$timeout_seconds" =~ ^[0-9]+$ ]] ||
  dbops_die "DBOPS_COMPATIBILITY_TIMEOUT_SECONDS must be numeric"
(( timeout_seconds >= 30 && timeout_seconds <= 600 )) ||
  dbops_die "DBOPS_COMPATIBILITY_TIMEOUT_SECONDS must be between 30 and 600"
rc_port="${RC_COMPATIBILITY_PORT:-18081}"
legacy_port="${LEGACY_COMPATIBILITY_PORT:-18082}"
for port in "$rc_port" "$legacy_port"; do
  [[ "$port" =~ ^[0-9]+$ && "$port" -ge 1024 && "$port" -le 65535 ]] ||
    dbops_die "compatibility ports must be integers from 1024 through 65535"
done
[[ "$rc_port" != "$legacy_port" ]] || dbops_die "RC and legacy compatibility ports must differ"

artifacts=(
  "${EVIDENCE_DIR}/compatibility-role-safety.tsv"
  "${EVIDENCE_DIR}/compatibility-role-safety.sha256"
  "${EVIDENCE_DIR}/compatibility-data-before.tsv"
  "${EVIDENCE_DIR}/compatibility-data-before.sha256"
  "${EVIDENCE_DIR}/compatibility-data-after.tsv"
  "${EVIDENCE_DIR}/compatibility-data-after.sha256"
  "${EVIDENCE_DIR}/compatibility-temp-cleanup.tsv"
  "${EVIDENCE_DIR}/compatibility-temp-cleanup.sha256"
  "${EVIDENCE_DIR}/backend-compatibility.tsv"
  "${EVIDENCE_DIR}/backend-compatibility.sha256"
  "${EVIDENCE_DIR}/http-contract-comparison.tsv"
  "${EVIDENCE_DIR}/http-contract-comparison.sha256"
  "${EVIDENCE_DIR}/rc-compatibility.log"
  "${EVIDENCE_DIR}/rc-compatibility.log.sha256"
  "${EVIDENCE_DIR}/legacy-compatibility.log"
  "${EVIDENCE_DIR}/legacy-compatibility.log.sha256"
)
dbops_assert_artifacts_absent "${artifacts[@]}"

verify_artifact() {
  local label="$1" jar="$2" expected_sha="$3"
  [[ -f "$jar" ]] || dbops_die "${label} JAR does not exist"
  local actual_sha
  actual_sha="$(sha256sum "$jar" | awk '{print $1}')"
  [[ "$actual_sha" == "${expected_sha,,}" ]] ||
    dbops_die "${label} JAR SHA-256 does not match"
  printf '%s' "$actual_sha"
}

rc_sha="$(verify_artifact rc "${RC_JAR}" "${RC_JAR_SHA256}")"
legacy_sha="$(verify_artifact legacy "${LEGACY_JAR}" "${LEGACY_JAR_SHA256}")"

sentinel_state="$(PGOPTIONS='-c default_transaction_read_only=on -c statement_timeout=10000 -c lock_timeout=3000' \
  dbops_psql --quiet --tuples-only --no-align \
  --set=sentinel_id="${CLONE_SENTINEL_ID}" \
  --set=source_snapshot_id="${SOURCE_SNAPSHOT_ID}" \
  --set=release_id="${RELEASE_ID}" \
  --set=evidence_seal_sha256="${migration_evidence_seal_sha256}" <<'SQL'
SELECT CASE
  WHEN baseline_started_at IS NULL THEN 'baseline-not-started'
  WHEN baseline_completed_at IS NULL THEN 'baseline-not-completed'
  WHEN evidence_seal_sha256 <> :'evidence_seal_sha256' THEN 'evidence-seal-mismatch'
  ELSE 'ready'
END
FROM preprod_guard.clone_sentinel
WHERE sentinel_id = :'sentinel_id'
  AND clone_kind = 'exact'
  AND source_snapshot_id = :'source_snapshot_id'
  AND release_id = :'release_id'
  AND destroyed_at IS NULL
  AND expires_at > CURRENT_TIMESTAMP;
SQL
)" || dbops_die "exact-clone compatibility sentinel query failed"
[[ "$sentinel_state" == "ready" ]] ||
  dbops_die "exact clone is not migration-complete (${sentinel_state:-missing})"

compat_psql() {
  PGUSER="${COMPAT_PGUSER}" PGPASSWORD="${COMPAT_PGPASSWORD}" \
    PGOPTIONS='-c default_transaction_read_only=on -c statement_timeout=300000 -c lock_timeout=3000 -c idle_in_transaction_session_timeout=300000' \
    dbops_psql "$@"
}

# The archived binary is untrusted startup code. Prove the separate account and
# session cannot write or SET ROLE before either JAR is started.
compat_psql --quiet --tuples-only --no-align --field-separator=$'\t' \
  --set=expected_database="${PGDATABASE}" \
  --set=expected_role="${COMPAT_PGUSER}" \
  --set=compatibility_member_id="${COMPAT_LOGIN_MEMBER_ID}" \
  --set=synthetic_email="compatibility-never-create@example.invalid" \
  >"${EVIDENCE_DIR}/compatibility-role-safety.tsv" <<'SQL'
SELECT check_name, violation_count
FROM (
  SELECT 'wrong_database' AS check_name,
         (current_database() <> :'expected_database')::int::bigint AS violation_count
  UNION ALL SELECT 'wrong_current_user',
         (current_user <> :'expected_role')::int::bigint
  UNION ALL SELECT 'wrong_session_user',
         (session_user <> :'expected_role')::int::bigint
  UNION ALL SELECT 'session_not_read_only',
         (current_setting('transaction_read_only') <> 'on')::int::bigint
  UNION ALL SELECT 'role_has_elevated_attributes',
         (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls)::int::bigint
  FROM pg_roles WHERE rolname = current_user
  UNION ALL SELECT 'database_create_privilege',
         has_database_privilege(current_user, current_database(), 'CREATE')::int::bigint
  UNION ALL SELECT 'missing_database_temp_privilege',
         (NOT has_database_privilege(current_user, current_database(), 'TEMP'))::int::bigint
  UNION ALL SELECT 'application_schema_create_privilege', COUNT(*)::bigint
  FROM pg_namespace namespace
  WHERE namespace.nspname <> 'information_schema'
    AND namespace.nspname !~ '^pg_'
    AND has_schema_privilege(current_user, namespace.oid, 'CREATE')
  UNION ALL SELECT 'application_table_write_privilege', COUNT(*)::bigint
  FROM pg_class relation
  JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
  WHERE relation.relkind IN ('r', 'p', 'v', 'm', 'f')
    AND namespace.nspname <> 'information_schema'
    AND namespace.nspname !~ '^pg_'
    AND has_table_privilege(
      current_user, relation.oid,
      'INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')
  UNION ALL SELECT 'application_column_write_privilege', COUNT(*)::bigint
  FROM pg_class relation
  JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
  WHERE relation.relkind IN ('r', 'p', 'v', 'm', 'f')
    AND namespace.nspname <> 'information_schema'
    AND namespace.nspname !~ '^pg_'
    AND has_any_column_privilege(
      current_user, relation.oid,
      'INSERT,UPDATE,REFERENCES')
  UNION ALL SELECT 'application_sequence_use_or_write_privilege', COUNT(*)::bigint
  FROM pg_class sequence
  JOIN pg_namespace namespace ON namespace.oid = sequence.relnamespace
  WHERE sequence.relkind = 'S'
    AND namespace.nspname <> 'information_schema'
    AND namespace.nspname !~ '^pg_'
    AND has_sequence_privilege(current_user, sequence.oid, 'USAGE,UPDATE')
  UNION ALL SELECT 'executable_security_definer_path', COUNT(*)::bigint
  FROM pg_proc routine
  JOIN pg_namespace namespace ON namespace.oid = routine.pronamespace
  WHERE routine.prosecdef
    AND namespace.nspname <> 'information_schema'
    AND namespace.nspname !~ '^pg_'
    AND has_function_privilege(current_user, routine.oid, 'EXECUTE')
  UNION ALL SELECT 'pg_write_all_data_membership',
         pg_has_role(current_user, 'pg_write_all_data', 'MEMBER')::int::bigint
  UNION ALL SELECT 'set_role_escalation_membership', COUNT(*)::bigint
  FROM (
    WITH RECURSIVE settable_roles(roleid) AS (
      SELECT membership.roleid
      FROM pg_auth_members membership
      JOIN pg_roles member_role ON member_role.oid = membership.member
      WHERE member_role.rolname = session_user
        AND (membership.set_option OR membership.admin_option)
      UNION
      SELECT membership.roleid
      FROM pg_auth_members membership
      JOIN settable_roles parent ON parent.roleid = membership.member
      WHERE membership.set_option OR membership.admin_option
    )
    SELECT role.rolname
    FROM settable_roles reachable
    JOIN pg_roles role ON role.oid = reachable.roleid
    WHERE role.rolname NOT IN ('pg_read_all_data', 'pg_read_all_settings',
                               'pg_read_all_stats', 'pg_monitor', 'pg_stat_scan_tables')
  ) elevated
  UNION ALL SELECT 'synthetic_login_email_exists', COUNT(*)::bigint
  FROM members WHERE lower(email) = lower(:'synthetic_email')
  UNION ALL SELECT 'approved_compatibility_account_id_count_not_one',
         (COUNT(*) <> 1)::int::bigint
  FROM members
  WHERE id = :'compatibility_member_id'::integer
    AND email_verified = TRUE
    AND status = 'APPROVED'
) checks
ORDER BY check_name;
SQL
role_violations="$(awk -F '\t' '{ total += $2 } END { print total + 0 }' \
  "${EVIDENCE_DIR}/compatibility-role-safety.tsv")"
sha256sum "${EVIDENCE_DIR}/compatibility-role-safety.tsv" \
  >"${EVIDENCE_DIR}/compatibility-role-safety.sha256"
[[ "$role_violations" == "0" ]] || {
  cat "${EVIDENCE_DIR}/compatibility-role-safety.tsv"
  dbops_die "compatibility role/session safety gate found ${role_violations} violations"
}

capture_data_fingerprint() {
  local phase="$1"
  local output="${EVIDENCE_DIR}/compatibility-data-${phase}.tsv"
  PGOPTIONS='-c default_transaction_read_only=on -c statement_timeout=300000 -c lock_timeout=3000' \
    dbops_psql --quiet --tuples-only --no-align --field-separator=$'\t' >"$output" <<'SQL'
WITH structural_items AS (
  SELECT format('relation|%I.%I|%s|%s|%s', namespace.nspname, relation.relname,
                relation.relkind, relation.relrowsecurity, relation.relforcerowsecurity) AS item
  FROM pg_class relation
  JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
  WHERE namespace.nspname <> 'information_schema'
    AND namespace.nspname !~ '^pg_'
  UNION ALL
  SELECT format('column|%I.%I|%s|%s|%s|%s', namespace.nspname, relation.relname,
                attribute.attname, format_type(attribute.atttypid, attribute.atttypmod),
                attribute.attnotnull,
                COALESCE(pg_get_expr(default_value.adbin, default_value.adrelid), ''))
  FROM pg_attribute attribute
  JOIN pg_class relation ON relation.oid = attribute.attrelid
  JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
  LEFT JOIN pg_attrdef default_value
    ON default_value.adrelid = attribute.attrelid
   AND default_value.adnum = attribute.attnum
  WHERE namespace.nspname <> 'information_schema'
    AND namespace.nspname !~ '^pg_'
    AND attribute.attnum > 0
    AND NOT attribute.attisdropped
  UNION ALL
  SELECT format('constraint|%I.%I|%s|%s', namespace.nspname, relation.relname,
                constraint_value.conname,
                pg_get_constraintdef(constraint_value.oid, true))
  FROM pg_constraint constraint_value
  JOIN pg_class relation ON relation.oid = constraint_value.conrelid
  JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
  WHERE namespace.nspname <> 'information_schema'
    AND namespace.nspname !~ '^pg_'
  UNION ALL
  SELECT format('index|%I.%I|%s', namespace.nspname, relation.relname,
                pg_get_indexdef(index_value.indexrelid))
  FROM pg_index index_value
  JOIN pg_class relation ON relation.oid = index_value.indrelid
  JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
  WHERE namespace.nspname <> 'information_schema'
    AND namespace.nspname !~ '^pg_'
  UNION ALL
  SELECT format('trigger|%I.%I|%s', namespace.nspname, relation.relname,
                pg_get_triggerdef(trigger_value.oid, true))
  FROM pg_trigger trigger_value
  JOIN pg_class relation ON relation.oid = trigger_value.tgrelid
  JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
  WHERE namespace.nspname <> 'information_schema'
    AND namespace.nspname !~ '^pg_'
    AND NOT trigger_value.tgisinternal
)
SELECT '__non_system_structure__', COUNT(*)::bigint,
       md5(COALESCE(string_agg(item, '' ORDER BY item), ''))
FROM structural_items;

SELECT format(
  'SELECT %L, count(*)::bigint, md5(COALESCE(string_agg(row_hash, %L ORDER BY row_hash), %L))'
  ' FROM (SELECT md5(row_to_json(value)::text) AS row_hash FROM %I.%I value) fingerprints;',
  table_schema || '.' || table_name, '', '', table_schema, table_name
)
FROM information_schema.tables
WHERE table_schema <> 'information_schema'
  AND table_schema !~ '^pg_'
  AND table_type = 'BASE TABLE'
ORDER BY table_schema, table_name
\gexec

SELECT 'sequence|' || schemaname || '.' || sequencename,
       1::bigint,
       md5(concat_ws('|', start_value, min_value, max_value, increment_by,
                     cycle, cache_size, last_value))
FROM pg_sequences
WHERE schemaname <> 'information_schema'
  AND schemaname !~ '^pg_'
ORDER BY schemaname, sequencename;
SQL
  sha256sum "$output" >"${EVIDENCE_DIR}/compatibility-data-${phase}.sha256"
}

capture_data_fingerprint before

database_url="jdbc:postgresql://${PGHOST}:${PGPORT}/${PGDATABASE}?sslmode=${PGSSLMODE}&currentSchema=public"
export SPRING_DATASOURCE_USERNAME="${COMPAT_PGUSER}"
export SPRING_DATASOURCE_PASSWORD="${COMPAT_PGPASSWORD}"

json_shape_hash() {
  local kind="$1" body_file="$2"
  "$python_bin" - "$kind" "$body_file" <<'PY'
import hashlib
import json
import sys

kind, path = sys.argv[1:]
with open(path, "r", encoding="utf-8") as stream:
    value = json.load(stream)

if kind == "landing":
    if not isinstance(value, dict) or not isinstance(value.get("campaigns"), list):
        raise SystemExit("landing response is not an object with a campaigns array")
elif kind == "login-invalid":
    if not isinstance(value, dict) or value.get("code") != "INVALID_CREDENTIALS":
        raise SystemExit("login response is not the expected INVALID_CREDENTIALS error")
elif kind == "login-success":
    if not isinstance(value, dict) or not isinstance(value.get("message"), str):
        raise SystemExit("successful login response is not the expected message object")
else:
    raise SystemExit(f"unsupported response-shape kind: {kind}")

def shape(item):
    if isinstance(item, dict):
        return {key: shape(item[key]) for key in sorted(item)}
    if isinstance(item, list):
        # A response can legitimately contain heterogeneous null/non-null rows.
        # Hash every distinct element shape, not only the first row, so a contract
        # change cannot hide behind database ordering.
        distinct = {
            json.dumps(shape(value), sort_keys=True, separators=(",", ":"))
            for value in item
        }
        return {"array": [json.loads(value) for value in sorted(distinct)]}
    if item is None:
        return "null"
    if isinstance(item, bool):
        return "boolean"
    if isinstance(item, (int, float)):
        return "number"
    return "string"

canonical = json.dumps(shape(value), sort_keys=True, separators=(",", ":"))
print(hashlib.sha256(canonical.encode("utf-8")).hexdigest())
PY
}

cookie_shape_hash() {
  local headers_file="$1"
  "$python_bin" - "$headers_file" <<'PY'
import hashlib
import json
import re
import sys

headers = []
with open(sys.argv[1], "r", encoding="iso-8859-1") as stream:
    for raw_line in stream:
        if raw_line.lower().startswith("set-cookie:"):
            headers.append(raw_line.split(":", 1)[1].strip())

cookies = []
for header in headers:
    parts = [part.strip() for part in header.split(";") if part.strip()]
    if not parts or "=" not in parts[0]:
        raise SystemExit("malformed Set-Cookie header")
    name, value = parts[0].split("=", 1)
    if not re.fullmatch(r"[A-Za-z0-9_-]+", name) or not value:
        raise SystemExit("malformed or empty authentication cookie")
    attributes = []
    for part in parts[1:]:
        if "=" in part:
            attribute_name, attribute_value = part.split("=", 1)
            attribute_name = attribute_name.strip().lower()
            attribute_value = attribute_value.strip()
            if attribute_name == "expires":
                attribute_value = "http-date"
            attributes.append([attribute_name, attribute_value])
        else:
            attributes.append([part.lower(), True])
    cookies.append({"name": name, "attributes": sorted(attributes)})

if sorted(cookie["name"] for cookie in cookies) != ["access_token", "refresh_token"]:
    raise SystemExit("Set-Cookie must issue exactly access_token and refresh_token")
canonical = json.dumps(sorted(cookies, key=lambda item: item["name"]),
                       sort_keys=True, separators=(",", ":"))
print(hashlib.sha256(canonical.encode("utf-8")).hexdigest())
PY
}

combined_shape_hash() {
  local body_shape_sha256="$1" cookie_shape_sha256="$2"
  printf 'body=%s\ncookies=%s\n' "$body_shape_sha256" "$cookie_shape_sha256" |
    sha256sum | awk '{print $1}'
}

assert_compatibility_log_redacted() {
  local log_file="$1" cookie_jar="$2" login_headers="$3" refresh_headers="$4" csrf_config="$5"
  "$python_bin" - "$log_file" "$cookie_jar" "$login_headers" "$refresh_headers" "$csrf_config" <<'PY'
import os
import sys

log = open(sys.argv[1], "r", encoding="utf-8", errors="replace").read()
secrets = [
    os.environ.get("COMPAT_LOGIN_EMAIL", ""),
    os.environ.get("COMPAT_LOGIN_PASSWORD", ""),
]
with open(sys.argv[2], "r", encoding="utf-8", errors="replace") as stream:
    for line in stream:
        if line.startswith("#HttpOnly_"):
            line = line[len("#HttpOnly_"):]
        elif line.startswith("#"):
            continue
        fields = line.rstrip("\n").split("\t")
        if len(fields) >= 7 and fields[6]:
            secrets.append(fields[6])
for path in sys.argv[3:5]:
    with open(path, "r", encoding="iso-8859-1") as stream:
        for line in stream:
            if line.lower().startswith("set-cookie:"):
                cookie_pair = line.split(":", 1)[1].strip().split(";", 1)[0]
                if "=" in cookie_pair:
                    secrets.append(cookie_pair.split("=", 1)[1])
with open(sys.argv[5], "r", encoding="utf-8") as stream:
    csrf_config = stream.read().strip()
if ":" in csrf_config:
    secrets.append(csrf_config.rsplit(":", 1)[1].strip().rstrip('"'))
for secret in secrets:
    if len(secret) >= 8 and secret in log:
        raise SystemExit("compatibility log contains a credential or cookie value")
PY
}

run_http_artifact() {
  local label="$1" jar="$2" port="$3" mode="$4"
  local log_file="${EVIDENCE_DIR}/${label}-compatibility.log"
  local temp_dir
  temp_dir="$(mktemp -d)"
  local process_id=""

  stop_artifact() {
    if [[ -n "$process_id" ]] && kill -0 "$process_id" 2>/dev/null; then
      kill "$process_id" 2>/dev/null || true
      wait "$process_id" 2>/dev/null || true
    fi
    process_id=""
  }

  cleanup_artifact() {
    stop_artifact
    if [[ "$temp_dir" == /tmp/* || "$temp_dir" == "${TMPDIR:-/tmp}"/* ]]; then
      rm -rf -- "$temp_dir"
    else
      printf 'REFUSED: unexpected compatibility temp path; not deleting %s\n' \
        "$temp_dir" >&2
    fi
    return 0
  }
  trap cleanup_artifact EXIT

  local -a common_args mode_args
  # One physical connection is intentional: every JDBC session receives private
  # pg_temp shadows before Flyway/Hibernate/application SQL can run. The role has
  # no persistent DML, schema-create, sequence-use or SET ROLE path, while the
  # temp member row permits refresh's SELECT FOR UPDATE and the two auth/audit
  # shadows absorb all successful login/rotation writes.
  local connection_init_sql
  connection_init_sql="SET default_transaction_read_only TO off; \
CREATE TEMP TABLE members (LIKE public.members INCLUDING ALL) ON COMMIT PRESERVE ROWS; \
INSERT INTO pg_temp.members SELECT * FROM public.members WHERE id = ${COMPAT_LOGIN_MEMBER_ID}; \
CREATE TEMP TABLE refresh_tokens (LIKE public.refresh_tokens INCLUDING ALL) ON COMMIT PRESERVE ROWS; \
CREATE TEMP TABLE audit_logs (LIKE public.audit_logs INCLUDING ALL) ON COMMIT PRESERVE ROWS; \
CREATE TEMP SEQUENCE audit_logs_id_seq; \
ALTER TABLE pg_temp.audit_logs ALTER COLUMN id SET DEFAULT nextval('pg_temp.audit_logs_id_seq')"
  common_args=(
    --spring.main.web-application-type=servlet
    --spring.main.banner-mode=off
    --spring.datasource.url="$database_url"
    --server.address=127.0.0.1
    --server.port="$port"
    --spring.jpa.hibernate.ddl-auto=validate
    --spring.datasource.hikari.read-only=false
    "--spring.datasource.hikari.connection-init-sql=${connection_init_sql}"
    --spring.datasource.hikari.maximum-pool-size=1
    --spring.datasource.hikari.minimum-idle=1
    --spring.task.scheduling.enabled=false
    --app.scheduling.enabled=false
    --instagram.sync.enabled=false
    --instagram.oauth-state.cleanup-enabled=false
    --instagram.webhook.cleanup-enabled=false
    --notification.outbox.enabled=false
    --notification.outbox.dispatch-enabled=false
    --features.payments.enabled=false
    --features.instagram.enabled=false
    --features.uploads.enabled=false
    --email.delivery-mode=disabled
    --email.allowed-recipients=
    --resend.api-key=
    --sentry.dsn=
    --rate-limit.backend=local
    --spring.data.redis.url=redis://127.0.0.1:1
    --jwt.secret=compatibility-only-jwt-secret-not-for-production-32-bytes
    --app.url=http://127.0.0.1
    --cors.allowed-origins=http://127.0.0.1
    --demo.bootstrap.enabled=false
    --admin.bootstrap.email=
    --admin.bootstrap.password=
  )
  if [[ "$mode" == "rc" ]]; then
    mode_args=(
      --app.environment=preproduction
      --app.exact-compatibility.enabled=true
      --app.release-id="${RELEASE_ID}"
      --app.git-commit-sha="${GIT_COMMIT_SHA}"
      --app.build-time="${BUILD_TIME}"
      --app.preproduction-database.clone-kind=exact
      --app.preproduction-database.sentinel-id="${CLONE_SENTINEL_ID}"
      --app.preproduction-database.source-snapshot-id="${SOURCE_SNAPSHOT_ID}"
      --app.preproduction-database.allowed-hosts="${CLONE_ALLOWED_HOSTS}"
      --app.preproduction-database.allowed-databases="${CLONE_ALLOWED_DATABASES}"
      --app.preproduction-database.production-host="${PRODUCTION_DB_HOST}"
      --app.preproduction-database.production-database="${PRODUCTION_DB_NAME}"
      --app.preproduction-database.database-confirmation="${DBOPS_CONFIRMATION}"
      --app.preproduction-database.evidence-seal-sha256="${migration_evidence_seal_sha256}"
      --spring.flyway.enabled=true
      --spring.flyway.baseline-on-migrate=false
      --files.storage=disabled
      --payments.gateway=disabled
      --instagram.provider=disabled
      --instagram.environment=preproduction
      --email.mock=false
    )
  else
    mode_args=(
      --app.environment=test
      --spring.flyway.enabled=false
      --files.storage=local
      --files.local.directory="${temp_dir}/files"
      --files.public-base-url=http://127.0.0.1
      --files.signing-secret=compatibility-only-file-secret-not-for-production
      --payments.gateway=mock
      --instagram.provider=mock
      --instagram.environment=test
      --email.mock=true
    )
  fi

  timeout --signal=TERM --kill-after=20s "${timeout_seconds}s" \
    java -jar "$jar" "${common_args[@]}" "${mode_args[@]}" \
    >"$log_file" 2>&1 &
  process_id=$!

  ready=false
  for _ in {1..60}; do
    if ! kill -0 "$process_id" 2>/dev/null; then
      break
    fi
    if curl --silent --show-error --fail --max-time 5 \
        -o /dev/null "http://127.0.0.1:${port}/landing/featured-campaigns"; then
      ready=true
      break
    fi
    sleep 1
  done
  [[ "$ready" == "true" ]] || dbops_die "${label} backend did not become ready on loopback"
  grep -Eq 'Started .*MarketingBackendApplication|Started MarketingBackendApplication' "$log_file" ||
    dbops_die "${label} backend did not reach successful Hibernate-validated startup"
  ! grep -Fq 'APPLICATION FAILED TO START' "$log_file" ||
    dbops_die "${label} backend reported failed startup"

  landing_status="$(curl --silent --show-error --max-time 15 \
    --output "${temp_dir}/landing.json" --write-out '%{http_code}' \
    "http://127.0.0.1:${port}/landing/featured-campaigns")"
  [[ "$landing_status" == "200" ]] ||
    dbops_die "${label} public landing smoke returned HTTP ${landing_status}"
  landing_shape="$(json_shape_hash landing "${temp_dir}/landing.json")"

  csrf_status="$(curl --silent --show-error --max-time 15 \
    --cookie-jar "${temp_dir}/cookies.txt" \
    --output "${temp_dir}/csrf.json" --write-out '%{http_code}' \
    "http://127.0.0.1:${port}/auth/csrf")"
  [[ "$csrf_status" == "200" ]] || dbops_die "${label} CSRF bootstrap failed"
  "$python_bin" - "${temp_dir}/csrf.json" "${temp_dir}/csrf.curl-config" <<'PY'
import json
import os
import re
import sys
with open(sys.argv[1], "r", encoding="utf-8") as stream:
    token = json.load(stream).get("token", "")
if not isinstance(token, str) or not re.fullmatch(r"[A-Za-z0-9._~-]+", token):
    raise SystemExit("missing CSRF token")
descriptor = os.open(sys.argv[2], os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
    stream.write(f'header = "X-XSRF-TOKEN: {token}"\n')
PY
  chmod 600 "${temp_dir}/csrf.curl-config"

  invalid_login_status="$(curl --silent --show-error --max-time 15 \
    --cookie "${temp_dir}/cookies.txt" \
    --config "${temp_dir}/csrf.curl-config" \
    --header 'Content-Type: application/json' \
    --data '{"email":"compatibility-never-create@example.invalid","password":"NeverUseThisSyntheticPassword-2026"}' \
    --output "${temp_dir}/login-invalid.json" --write-out '%{http_code}' \
    "http://127.0.0.1:${port}/auth/login")"
  [[ "$invalid_login_status" == "401" ]] ||
    dbops_die "${label} nonexistent-account login returned HTTP ${invalid_login_status}"
  invalid_login_shape="$(json_shape_hash login-invalid "${temp_dir}/login-invalid.json")"

  "$python_bin" - "${temp_dir}/approved-login.json" <<'PY'
import json
import os
import sys

path = sys.argv[1]
descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
    json.dump({
        "email": os.environ["COMPAT_LOGIN_EMAIL"],
        "password": os.environ["COMPAT_LOGIN_PASSWORD"],
    }, stream, separators=(",", ":"))
PY
  chmod 600 "${temp_dir}/approved-login.json" "${temp_dir}/cookies.txt"

  success_login_status="$(curl --silent --show-error --max-time 15 \
    --cookie "${temp_dir}/cookies.txt" \
    --cookie-jar "${temp_dir}/cookies.txt" \
    --dump-header "${temp_dir}/login-success.headers" \
    --config "${temp_dir}/csrf.curl-config" \
    --header 'Content-Type: application/json' \
    --data-binary "@${temp_dir}/approved-login.json" \
    --output "${temp_dir}/login-success.json" --write-out '%{http_code}' \
    "http://127.0.0.1:${port}/auth/login")"
  [[ "$success_login_status" == "200" ]] ||
    dbops_die "${label} approved compatibility-account login returned HTTP ${success_login_status}"
  success_login_body_shape="$(json_shape_hash login-success "${temp_dir}/login-success.json")"
  success_login_cookie_shape="$(cookie_shape_hash "${temp_dir}/login-success.headers")"
  success_login_shape="$(combined_shape_hash \
    "$success_login_body_shape" "$success_login_cookie_shape")"

  refresh_status="$(curl --silent --show-error --max-time 15 \
    --request POST \
    --cookie "${temp_dir}/cookies.txt" \
    --cookie-jar "${temp_dir}/cookies.txt" \
    --dump-header "${temp_dir}/refresh-success.headers" \
    --config "${temp_dir}/csrf.curl-config" \
    --output "${temp_dir}/refresh-success.body" --write-out '%{http_code}' \
    "http://127.0.0.1:${port}/auth/refresh")"
  [[ "$refresh_status" == "204" ]] ||
    dbops_die "${label} approved compatibility-account refresh returned HTTP ${refresh_status}"
  refresh_shape="$(cookie_shape_hash "${temp_dir}/refresh-success.headers")"

  printf '%s\tstarted-hibernate-validated-readonly-temp-auth\tlanding:%s:%s\tloginInvalid:%s:%s\tloginSuccess:%s:%s\trefreshSuccess:%s:%s\n' \
    "$label" "$landing_status" "$landing_shape" \
    "$invalid_login_status" "$invalid_login_shape" \
    "$success_login_status" "$success_login_shape" \
    "$refresh_status" "$refresh_shape" \
    >>"${EVIDENCE_DIR}/backend-compatibility.tsv"
  stop_artifact
  assert_compatibility_log_redacted \
    "$log_file" "${temp_dir}/cookies.txt" \
    "${temp_dir}/login-success.headers" "${temp_dir}/refresh-success.headers" \
    "${temp_dir}/csrf.curl-config"
  cleanup_artifact
  trap - EXIT
  sha256sum "$log_file" >"${log_file}.sha256"
}

: >"${EVIDENCE_DIR}/backend-compatibility.tsv"
run_http_artifact rc "${RC_JAR}" "$rc_port" rc
run_http_artifact legacy "${LEGACY_JAR}" "$legacy_port" legacy

PGOPTIONS='-c default_transaction_read_only=on -c statement_timeout=10000 -c lock_timeout=3000' \
  dbops_psql --quiet --tuples-only --no-align --field-separator=$'\t' \
  >"${EVIDENCE_DIR}/compatibility-temp-cleanup.tsv" <<'SQL'
SELECT 'orphaned_compatibility_temp_relations', COUNT(*)::bigint
FROM pg_class relation
JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
WHERE relation.relpersistence = 't'
  AND namespace.nspname ~ '^pg_temp_'
  AND relation.relname IN (
    'members', 'refresh_tokens', 'audit_logs', 'audit_logs_id_seq'
  );
SQL
temp_cleanup_violations="$(awk -F '\t' '{ total += $2 } END { print total + 0 }' \
  "${EVIDENCE_DIR}/compatibility-temp-cleanup.tsv")"
sha256sum "${EVIDENCE_DIR}/compatibility-temp-cleanup.tsv" \
  >"${EVIDENCE_DIR}/compatibility-temp-cleanup.sha256"
[[ "$temp_cleanup_violations" == "0" ]] ||
  dbops_die "compatibility JDBC temp objects survived backend process termination"

if ! compare_http_contract_evidence \
    "${EVIDENCE_DIR}/backend-compatibility.tsv" \
    "${EVIDENCE_DIR}/http-contract-comparison.tsv"; then
  dbops_die "RC and legacy HTTP status/response contract shapes differ"
fi
sha256sum "${EVIDENCE_DIR}/http-contract-comparison.tsv" \
  >"${EVIDENCE_DIR}/http-contract-comparison.sha256"

capture_data_fingerprint after
cmp -s "${EVIDENCE_DIR}/compatibility-data-before.tsv" \
  "${EVIDENCE_DIR}/compatibility-data-after.tsv" ||
  dbops_die "exact-clone rows changed during compatibility startup/API smoke"

printf 'releaseId\t%s\nrcJarSha256\t%s\nlegacyJarSha256\t%s\n' \
  "${RELEASE_ID}" "${rc_sha}" "${legacy_sha}" \
  >>"${EVIDENCE_DIR}/backend-compatibility.tsv"
printf 'databaseRole\t%s\nflyway\trc-validate-only;legacy-disabled\n' \
  "${COMPAT_PGUSER}" >>"${EVIDENCE_DIR}/backend-compatibility.tsv"
printf 'approvedLoginEmailSha256\t%s\n' "${COMPAT_LOGIN_EMAIL_SHA256}" \
  >>"${EVIDENCE_DIR}/backend-compatibility.tsv"
printf 'httpContract\trc-legacy-landing-invalid-login-approved-login-refresh-status-and-shape-matched\n' \
  >>"${EVIDENCE_DIR}/backend-compatibility.tsv"
printf 'databaseFingerprint\tunchanged\ntemporaryAuthObjects\tdestroyed\ncompletedAtUtc\t%s\n' \
  "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  >>"${EVIDENCE_DIR}/backend-compatibility.tsv"
sha256sum "${EVIDENCE_DIR}/backend-compatibility.tsv" \
  >"${EVIDENCE_DIR}/backend-compatibility.sha256"
EVIDENCE_STAGE=exact-compatibility bash "${SCRIPT_DIR}/seal-evidence.sh"

printf 'New and legacy backend HTTP compatibility passed with temp-only auth writes and no persistent data changes.\n'
