#!/usr/bin/env bash
set -Eeuo pipefail

# Deliberately separate from clone_guard.sh: this command is the only database
# automation allowed to identify the declared production target, and it never
# starts the application, Flyway, Hibernate, or any DDL/DML command.

readonly REQUIRED_CONFIRMATION='I_ACKNOWLEDGE_PRODUCTION_READ_ONLY_METADATA_AUDIT_ONLY'
readonly SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"

die() {
  printf 'REFUSED: %s\n' "$*" >&2
  exit 64
}

require_env() {
  local name="$1"
  [[ -n "${!name:-}" ]] || die "required environment variable ${name} is empty"
}

normalize_host() {
  printf '%s' "$1" | tr '[:upper:]' '[:lower:]' | sed 's/[.]$//'
}

for name in PGHOST PGPORT PGDATABASE PGUSER PGSSLMODE PGPASSWORD \
  PRODUCTION_DB_HOST PRODUCTION_DB_NAME PRODUCTION_READONLY_ROLE \
  PRODUCTION_AUDIT_CONFIRMATION RELEASE_ID EVIDENCE_DIR; do
  require_env "$name"
done

[[ "${PRODUCTION_AUDIT_CONFIRMATION}" == "${REQUIRED_CONFIRMATION}" ]] ||
  die "PRODUCTION_AUDIT_CONFIRMATION does not match the required production read-only phrase"
[[ "${RELEASE_ID}" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$ ]] ||
  die "RELEASE_ID must be a safe 1-128 character release identifier"
[[ "${PGHOST}" =~ ^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?[.]?$ \
    && "${PGHOST}" != *..* ]] ||
  die "PGHOST must be one explicit DNS hostname, not a socket, URI, or host list"
[[ "${PGDATABASE}" =~ ^[A-Za-z0-9][A-Za-z0-9_-]*$ ]] ||
  die "PGDATABASE must be a plain database identifier, not conninfo or a URI"
[[ "${PGUSER}" =~ ^[A-Za-z0-9][A-Za-z0-9_.-]*$ ]] ||
  die "PGUSER must be a plain role identifier"
[[ "$(normalize_host "${PGHOST}")" == "$(normalize_host "${PRODUCTION_DB_HOST}")" ]] ||
  die "target host is not exactly the declared production host"
[[ "${PGDATABASE}" == "${PRODUCTION_DB_NAME}" ]] ||
  die "target database is not exactly the declared production database"
[[ "${PGUSER}" == "${PRODUCTION_READONLY_ROLE}" ]] ||
  die "PGUSER is not exactly PRODUCTION_READONLY_ROLE"
[[ "${PGSSLMODE}" == "verify-full" ]] ||
  die "production audit requires PGSSLMODE=verify-full"
[[ "${PGPORT}" =~ ^[0-9]+$ && "${PGPORT}" -ge 1 && "${PGPORT}" -le 65535 ]] ||
  die "PGPORT must be an integer from 1 through 65535"
[[ "${EVIDENCE_DIR}" != "/" && "${EVIDENCE_DIR}" != "." ]] ||
  die "EVIDENCE_DIR must be a dedicated directory"
[[ ! -L "${EVIDENCE_DIR}" ]] || die "EVIDENCE_DIR must not be a symbolic link"
[[ ! -e "${EVIDENCE_DIR}" ]] ||
  die "production audit EVIDENCE_DIR must be a new path for this release and run"
command -v psql >/dev/null 2>&1 || die "psql is required"
command -v sha256sum >/dev/null 2>&1 || die "sha256sum is required"

unset PGSERVICE PGSERVICEFILE PGHOSTADDR PGOPTIONS
export PGCONNECT_TIMEOUT=10

umask 077
mkdir -p "${EVIDENCE_DIR}"
for artifact in safety-gate.tsv database-metadata.tsv schema-fingerprint.tsv \
  row-counts.tsv preflight-counts.tsv flyway-state.tsv SHA256SUMS; do
  [[ ! -e "${EVIDENCE_DIR}/${artifact}" ]] ||
    die "refusing to overwrite existing evidence artifact ${artifact}"
done

export PGAPPNAME='viralground-production-readonly-audit'
readonly SAFE_PGOPTIONS='-c default_transaction_read_only=on -c statement_timeout=15000 -c lock_timeout=1000 -c idle_in_transaction_session_timeout=15000 -c search_path=public'

readonly_psql() {
  PGOPTIONS="${SAFE_PGOPTIONS}" psql -X --no-password --set=ON_ERROR_STOP=1 \
    --set=VERBOSITY=terse "$@"
}

# This first query is the hard gate. No later metadata query runs unless the
# server confirms the exact target/role and a non-privileged read-only session.
readonly_psql --quiet --tuples-only --no-align --field-separator=$'\t' \
  --set=expected_database="${PRODUCTION_DB_NAME}" \
  --set=expected_role="${PRODUCTION_READONLY_ROLE}" \
  >"${EVIDENCE_DIR}/safety-gate.tsv" <<'SQL'
SELECT check_name, violation_count
FROM (
  SELECT 'wrong_database' AS check_name,
         (current_database() <> :'expected_database')::int::bigint AS violation_count
  UNION ALL
  SELECT 'wrong_role', (current_user <> :'expected_role')::int::bigint
  UNION ALL
  SELECT 'wrong_session_user', (session_user <> :'expected_role')::int::bigint
  UNION ALL
  SELECT 'session_not_read_only',
         (current_setting('transaction_read_only') <> 'on')::int::bigint
  UNION ALL
  SELECT 'role_is_superuser', rolsuper::int::bigint
  FROM pg_roles WHERE rolname = current_user
  UNION ALL
  SELECT 'role_can_create_database', rolcreatedb::int::bigint
  FROM pg_roles WHERE rolname = current_user
  UNION ALL
  SELECT 'role_can_create_role', rolcreaterole::int::bigint
  FROM pg_roles WHERE rolname = current_user
  UNION ALL
  SELECT 'role_can_replicate', rolreplication::int::bigint
  FROM pg_roles WHERE rolname = current_user
  UNION ALL
  SELECT 'role_can_bypass_rls', rolbypassrls::int::bigint
  FROM pg_roles WHERE rolname = current_user
  UNION ALL
  SELECT 'database_create_privilege',
         has_database_privilege(current_user, current_database(), 'CREATE')::int::bigint
  UNION ALL
  SELECT 'database_temp_privilege',
         has_database_privilege(current_user, current_database(), 'TEMP')::int::bigint
  UNION ALL
  SELECT 'pg_write_all_data_membership',
         pg_has_role(current_user, 'pg_write_all_data', 'MEMBER')::int::bigint
  UNION ALL
  SELECT 'dangerous_predefined_role_membership', COUNT(*)::bigint
  FROM pg_roles predefined_role
  WHERE predefined_role.rolname IN (
      'pg_read_server_files',
      'pg_write_server_files',
      'pg_execute_server_program',
      'pg_signal_backend',
      'pg_checkpoint',
      'pg_maintain',
      'pg_create_subscription'
    )
    AND pg_has_role(current_user, predefined_role.oid, 'MEMBER')
  UNION ALL
  SELECT 'set_role_escalation_membership', COUNT(*)::bigint
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
      JOIN settable_roles reachable ON reachable.roleid = membership.member
      WHERE membership.set_option OR membership.admin_option
    )
    SELECT DISTINCT target_role.oid
    FROM settable_roles reachable
    JOIN pg_roles target_role ON target_role.oid = reachable.roleid
    WHERE target_role.rolname NOT IN (
      'pg_read_all_data',
      'pg_read_all_settings',
      'pg_read_all_stats',
      'pg_monitor',
      'pg_stat_scan_tables'
    )
  ) unsafe_set_role_target
  UNION ALL
  SELECT 'writable_schema_privilege', COUNT(*)::bigint
  FROM pg_namespace
  WHERE nspname NOT LIKE 'pg\_%' ESCAPE '\'
    AND nspname <> 'information_schema'
    AND has_schema_privilege(current_user, oid, 'CREATE')
  UNION ALL
  SELECT 'direct_table_write_privilege', COUNT(*)::bigint
  FROM pg_class relation
  JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
  WHERE relation.relkind IN ('r', 'p', 'v', 'm', 'f')
    AND namespace.nspname NOT IN ('pg_catalog', 'information_schema')
    AND namespace.nspname NOT LIKE 'pg\_%' ESCAPE '\'
    AND has_table_privilege(
      current_user,
      relation.oid,
      'INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER'
    )
  UNION ALL
  SELECT 'column_write_privilege', COUNT(*)::bigint
  FROM pg_class relation
  JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
  WHERE relation.relkind IN ('r', 'p', 'v', 'm', 'f')
    AND namespace.nspname NOT IN ('pg_catalog', 'information_schema')
    AND namespace.nspname NOT LIKE 'pg\_%' ESCAPE '\'
    AND has_any_column_privilege(
      current_user,
      relation.oid,
      'INSERT,UPDATE,REFERENCES'
    )
  UNION ALL
  SELECT 'sequence_write_privilege', COUNT(*)::bigint
  FROM pg_class relation
  JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
  WHERE relation.relkind = 'S'
    AND namespace.nspname NOT IN ('pg_catalog', 'information_schema')
    AND namespace.nspname NOT LIKE 'pg\_%' ESCAPE '\'
    AND (
      has_sequence_privilege(current_user, relation.oid, 'USAGE')
      OR has_sequence_privilege(current_user, relation.oid, 'UPDATE')
    )
  UNION ALL
  SELECT 'executable_security_definer_path', COUNT(*)::bigint
  FROM pg_proc routine
  JOIN pg_namespace namespace ON namespace.oid = routine.pronamespace
  WHERE routine.prosecdef
    AND namespace.nspname <> 'information_schema'
    AND namespace.nspname NOT LIKE 'pg\_%' ESCAPE '\'
    AND has_function_privilege(current_user, routine.oid, 'EXECUTE')
  UNION ALL
  SELECT 'public_rls_enabled_or_forced', COUNT(*)::bigint
  FROM pg_class relation
  JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
  WHERE namespace.nspname = 'public'
    AND relation.relkind IN ('r', 'p')
    AND (relation.relrowsecurity OR relation.relforcerowsecurity)
  UNION ALL
  SELECT 'public_rls_policy_exists', COUNT(*)::bigint
  FROM pg_policy policy
  JOIN pg_class relation ON relation.oid = policy.polrelid
  JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
  WHERE namespace.nspname = 'public'
) checks
ORDER BY check_name;
SQL

violations="$(awk -F '\t' '{ total += $2 } END { print total + 0 }' \
  "${EVIDENCE_DIR}/safety-gate.tsv")"
if [[ "${violations}" != "0" ]]; then
  cat "${EVIDENCE_DIR}/safety-gate.tsv"
  die "production role/session safety gate found ${violations} violations; no audit queries ran"
fi

readonly_psql --quiet --tuples-only --no-align --field-separator=$'\t' \
  >"${EVIDENCE_DIR}/database-metadata.tsv" <<'SQL'
SELECT 'database', current_database()
UNION ALL SELECT 'role', current_user
UNION ALL SELECT 'session_user', session_user
UNION ALL SELECT 'server_version_num', current_setting('server_version_num')
UNION ALL SELECT 'timezone', current_setting('TimeZone')
UNION ALL SELECT 'transaction_read_only', current_setting('transaction_read_only')
ORDER BY 1;
SQL

# Hash only structural catalog metadata. No table row, function body, comment,
# default expression, or other potentially sensitive value is emitted.
readonly_psql --quiet --tuples-only --no-align --field-separator=$'\t' \
  >"${EVIDENCE_DIR}/schema-fingerprint.tsv" <<'SQL'
WITH structural_items AS (
  SELECT format('table|%I.%I|%s|rls=%s|forceRls=%s',
                namespace.nspname, relation.relname, relation.relkind,
                relation.relrowsecurity, relation.relforcerowsecurity) AS item
  FROM pg_class relation
  JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
  WHERE namespace.nspname = 'public' AND relation.relkind IN ('r', 'p', 'S')
  UNION ALL
  SELECT format(
    'column|%I.%I|%s|%s|%s',
    table_schema, table_name, column_name, data_type, is_nullable
  )
  FROM information_schema.columns WHERE table_schema = 'public'
  UNION ALL
  SELECT format('constraint|%I.%I|%s|%s|%s',
                namespace.nspname, relation.relname,
                constraint_record.conname, constraint_record.contype,
                constraint_record.convalidated)
  FROM pg_constraint constraint_record
  JOIN pg_class relation ON relation.oid = constraint_record.conrelid
  JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
  WHERE namespace.nspname = 'public'
  UNION ALL
  SELECT format('policy|%I.%I|%s|permissive=%s|command=%s',
                namespace.nspname, relation.relname, policy.polname,
                policy.polpermissive, policy.polcmd)
  FROM pg_policy policy
  JOIN pg_class relation ON relation.oid = policy.polrelid
  JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
  WHERE namespace.nspname = 'public'
)
SELECT 'structural_item_count', COUNT(*)::text FROM structural_items
UNION ALL
SELECT 'structural_md5', md5(string_agg(item, E'\n' ORDER BY item))
FROM structural_items
UNION ALL
SELECT 'extension', extname || ':' || extversion FROM pg_extension
ORDER BY 1, 2;
SQL

# Exact counts only; the generated statements never select a row value. The
# relation names come exclusively from the server catalog and are identifier-quoted.
readonly_psql --quiet --tuples-only --no-align --field-separator=$'\t' \
  >"${EVIDENCE_DIR}/row-counts.tsv" <<'SQL'
SELECT format(
  'SELECT %L, COUNT(*)::bigint FROM %I.%I;',
  namespace.nspname || '.' || relation.relname,
  namespace.nspname,
  relation.relname
)
FROM pg_class relation
JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
WHERE namespace.nspname = 'public'
  AND relation.relkind IN ('r', 'p')
  AND relation.relname <> 'flyway_schema_history'
ORDER BY relation.relname
\gexec
SQL

readonly_psql --quiet --tuples-only --no-align --field-separator=$'\t' \
  >"${EVIDENCE_DIR}/preflight-counts.tsv" <<'SQL'
SELECT check_name, severity, violation_count
FROM (
  SELECT 'production_demo_account_candidate' AS check_name, 'BLOCKER' AS severity,
         COUNT(*)::bigint AS violation_count
  FROM members
  WHERE lower(email) IN (
      'creator.demo@viralground.local',
      'company.demo@viralground.local'
    )
     OR name IN ('데모 크리에이터', '데모 기업 담당자')
  UNION ALL
  SELECT 'v3_nonpositive_escrow_amount', 'BLOCKER',
         COUNT(*)::bigint AS violation_count
  FROM escrow_transactions WHERE amount <= 0
  UNION ALL
  SELECT 'v3_unknown_escrow_type', 'BLOCKER', COUNT(*)
  FROM escrow_transactions WHERE type NOT IN ('DEPOSIT', 'RELEASE', 'REFUND')
  UNION ALL
  SELECT 'v3_duplicate_campaign_deposit', 'BLOCKER', COUNT(*)
  FROM (
    SELECT campaign_id FROM escrow_transactions WHERE type = 'DEPOSIT'
    GROUP BY campaign_id HAVING COUNT(*) > 1
  ) duplicate_deposit
  UNION ALL
  SELECT 'v3_duplicate_campaign_refund', 'BLOCKER', COUNT(*)
  FROM (
    SELECT campaign_id FROM escrow_transactions WHERE type = 'REFUND'
    GROUP BY campaign_id HAVING COUNT(*) > 1
  ) duplicate_refund
  UNION ALL
  SELECT 'v3_duplicate_application_release', 'BLOCKER', COUNT(*)
  FROM (
    SELECT campaign_id, application_id FROM escrow_transactions
    WHERE type = 'RELEASE'
    GROUP BY campaign_id, application_id HAVING COUNT(*) > 1
  ) duplicate_release
  UNION ALL
  SELECT 'v3_negative_running_balance', 'BLOCKER', COUNT(*)
  FROM (
    SELECT SUM(CASE WHEN type = 'DEPOSIT' THEN amount ELSE -amount END)
             OVER (PARTITION BY campaign_id ORDER BY created_at, id) AS running_balance
    FROM escrow_transactions
  ) balances
  WHERE running_balance < 0
  UNION ALL
  SELECT 'v4_duplicate_meta_account', 'BLOCKER', COUNT(*)
  FROM (
    SELECT provider_account_id
    FROM creator_instagram_connections
    WHERE provider_account_id IS NOT NULL
    GROUP BY provider_account_id HAVING COUNT(*) > 1
  ) duplicate_meta
  UNION ALL
  SELECT 'v9_invalid_member_status', 'BLOCKER', COUNT(*)
  FROM members WHERE status NOT IN ('PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN')
  UNION ALL
  SELECT 'v9_preexisting_withdrawn_member_without_timestamp', 'BLOCKER', COUNT(*)
  FROM members WHERE status = 'WITHDRAWN'
  UNION ALL
  SELECT 'v9_invalid_application_status', 'BLOCKER', COUNT(*)
  FROM campaign_applications
  WHERE status NOT IN (
    'PENDING', 'WITHDRAWN', 'APPROVED', 'REJECTED',
    'SUBMITTED', 'CHANGES_REQUESTED', 'SETTLED'
  )
  UNION ALL
  SELECT 'v11_invalid_campaign_budget', 'BLOCKER', COUNT(*)
  FROM campaigns
  WHERE reward_amount NOT BETWEEN 1 AND 100000000
     OR max_participants NOT BETWEEN 1 AND 10000
     OR total_budget <= 0
     OR total_budget::bigint <> reward_amount::bigint * max_participants::bigint
  UNION ALL
  SELECT 'v11_homepage_requires_cleanup', 'WARNING', COUNT(*)
  FROM company_profiles
  WHERE homepage IS NOT NULL
    AND (char_length(homepage) > 500 OR homepage !~ '^https://'
         OR homepage <> btrim(homepage) OR homepage ~ '[[:cntrl:]]'
         OR homepage ~ '^https://[^/?#]*@')
) checks
ORDER BY check_name;
SQL

readonly_psql --quiet --tuples-only --no-align --field-separator=$'\t' \
  >"${EVIDENCE_DIR}/flyway-state.tsv" <<'SQL'
SELECT (to_regclass('public.flyway_schema_history') IS NOT NULL)::text AS history_exists
\gset
SELECT 'history_table_exists', :'history_exists';
\if :history_exists
SELECT 'history_row_count', COUNT(*)::text FROM public.flyway_schema_history;
SELECT 'history_failed_count', COUNT(*)::text
FROM public.flyway_schema_history WHERE success = FALSE;
SELECT 'history_latest_version', COALESCE(MAX(version), 'none')
FROM public.flyway_schema_history WHERE success = TRUE;
\else
SELECT 'history_row_count', '0';
SELECT 'history_failed_count', '0';
SELECT 'history_latest_version', 'none';
\endif
SQL

(cd "${EVIDENCE_DIR}" && sha256sum safety-gate.tsv database-metadata.tsv \
  schema-fingerprint.tsv row-counts.tsv preflight-counts.tsv flyway-state.tsv >SHA256SUMS)

cat "${EVIDENCE_DIR}/safety-gate.tsv"
cat "${EVIDENCE_DIR}/preflight-counts.tsv"
EVIDENCE_STAGE='production-readonly-audit' \
  bash "${SCRIPT_DIR}/seal-evidence.sh"
EVIDENCE_STAGE='production-readonly-audit' \
  bash "${SCRIPT_DIR}/verify-evidence-seal.sh"
printf 'Production read-only metadata audit completed and release-bound evidence sealed. No application, migration, DDL, DML, or row sample was executed.\n'
