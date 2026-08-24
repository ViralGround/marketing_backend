#!/usr/bin/env bash

# Shared safety boundary for every pre-production database operation.
# This file intentionally does not load .env files. Callers must inject an
# explicit, short-lived clone credential through their secret manager.

set -Eeuo pipefail

dbops_die() {
  printf 'REFUSED: %s\n' "$*" >&2
  exit 64
}

dbops_require() {
  local name="$1"
  [[ -n "${!name:-}" ]] || dbops_die "required environment variable ${name} is empty"
}

dbops_normalize_host() {
  printf '%s' "$1" | tr '[:upper:]' '[:lower:]' | sed 's/[.]$//'
}

dbops_assert_libpq_identifiers() {
  [[ "${PGHOST}" =~ ^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?[.]?$ \
      && "${PGHOST}" != *..* ]] ||
    dbops_die "PGHOST must be one explicit DNS hostname, not a socket, URI, or host list"
  [[ "${PGPORT}" =~ ^[0-9]+$ && "${PGPORT}" -ge 1 && "${PGPORT}" -le 65535 ]] ||
    dbops_die "PGPORT must be an integer from 1 through 65535"
  [[ "${PGDATABASE}" =~ ^[A-Za-z0-9][A-Za-z0-9_-]*$ ]] ||
    dbops_die "PGDATABASE must be a plain database identifier, not conninfo or a URI"
  [[ "${PGUSER}" =~ ^[A-Za-z0-9][A-Za-z0-9_.-]*$ ]] ||
    dbops_die "PGUSER must be a plain role identifier"
}

dbops_list_contains() {
  local wanted="$1"
  local csv="$2"
  local item
  IFS=',' read -r -a dbops_items <<< "$csv"
  for item in "${dbops_items[@]}"; do
    item="$(printf '%s' "$item" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')"
    [[ "$item" == "$wanted" ]] && return 0
  done
  return 1
}

dbops_assert_static_target() {
  local required=(
    PGHOST PGPORT PGDATABASE PGUSER PGSSLMODE
    CLONE_KIND CLONE_SENTINEL_ID
    SOURCE_SNAPSHOT_ID
    CLONE_ALLOWED_HOSTS CLONE_ALLOWED_DATABASES
    PRODUCTION_DB_HOST PRODUCTION_DB_NAME
    DBOPS_CONFIRMATION RELEASE_ID
  )
  local name
  for name in "${required[@]}"; do
    dbops_require "$name"
  done

  [[ "${DBOPS_CONFIRMATION}" == "I_ACKNOWLEDGE_THIS_IS_A_DISPOSABLE_CLONE" ]] ||
    dbops_die "DBOPS_CONFIRMATION does not match the required phrase"

  dbops_assert_libpq_identifiers

  case "${CLONE_KIND}" in
    exact|sanitized) ;;
    *) dbops_die "CLONE_KIND must be exact or sanitized" ;;
  esac

  [[ "${SOURCE_SNAPSHOT_ID}" =~ ^[A-Za-z0-9][A-Za-z0-9._:/@+-]{0,255}$ ]] ||
    dbops_die "SOURCE_SNAPSHOT_ID must be one safe 1-256 character provider snapshot identifier"

  case "${PGSSLMODE}" in
    require|verify-ca|verify-full) ;;
    *) dbops_die "PGSSLMODE must be require, verify-ca, or verify-full" ;;
  esac

  local target_host production_host
  target_host="$(dbops_normalize_host "${PGHOST}")"
  production_host="$(dbops_normalize_host "${PRODUCTION_DB_HOST}")"

  if [[ ! "$target_host" =~ ^(localhost|127[.]0[.]0[.]1|::1)$ \
      && "${PGSSLMODE}" != "verify-full" ]]; then
    dbops_die "remote clone operations require PGSSLMODE=verify-full"
  fi

  [[ "$target_host" != "$production_host" ]] ||
    dbops_die "target host is the declared production host"
  [[ "${PGDATABASE}" != "${PRODUCTION_DB_NAME}" ]] ||
    dbops_die "target database is the declared production database"

  [[ ! "$target_host" =~ (^|[.-])(prod|production)([.-]|$) ]] ||
    dbops_die "target host contains a production marker"
  [[ ! "${PGDATABASE,,}" =~ (^|[_-])(prod|production)($|[_-]) ]] ||
    dbops_die "target database contains a production marker"

  dbops_list_contains "$target_host" "$(dbops_normalize_host "${CLONE_ALLOWED_HOSTS}")" ||
    dbops_die "target host is not in CLONE_ALLOWED_HOSTS"
  dbops_list_contains "${PGDATABASE}" "${CLONE_ALLOWED_DATABASES}" ||
    dbops_die "target database is not in CLONE_ALLOWED_DATABASES"

  if [[ "${CLONE_KIND}" == "exact" ]]; then
    [[ "${PGDATABASE}" == *_exact ]] || dbops_die "exact clone database must end in _exact"
  else
    [[ "${PGDATABASE}" == *_staging ]] || dbops_die "sanitized clone database must end in _staging"
  fi

  command -v psql >/dev/null 2>&1 || dbops_die "psql is required"
  # Refuse libpq side channels that could redirect the explicitly checked target
  # or run `SET ROLE` before the sentinel query. Per-query PGOPTIONS assignments
  # below remain available for read-only/time-limited evidence sessions.
  unset PGSERVICE PGSERVICEFILE PGHOSTADDR PGOPTIONS PGTZ
  export PGCONNECT_TIMEOUT=10
  export PGAPPNAME="viralground-preprod-dbops-${CLONE_KIND}"
}

dbops_psql() {
  local caller_options="${PGOPTIONS:-}"
  PGOPTIONS="${caller_options:+${caller_options} }-c search_path=public" \
    psql -X --no-password --set=ON_ERROR_STOP=1 --set=VERBOSITY=terse "$@"
}

dbops_verify_sentinel() {
  dbops_assert_static_target

  local result
  result="$(PGOPTIONS='-c default_transaction_read_only=on -c statement_timeout=10000 -c lock_timeout=3000' \
    dbops_psql --quiet --tuples-only --no-align \
    --set=expected_database="${PGDATABASE}" \
    --set=sentinel_id="${CLONE_SENTINEL_ID}" \
    --set=clone_kind="${CLONE_KIND}" \
    --set=source_snapshot_id="${SOURCE_SNAPSHOT_ID}" \
    --set=release_id="${RELEASE_ID}" <<'SQL'
SELECT CASE
  WHEN current_database() <> :'expected_database' THEN 'wrong-database'
  WHEN NOT EXISTS (
    SELECT 1
    FROM preprod_guard.clone_sentinel
    WHERE sentinel_id = :'sentinel_id'
      AND clone_kind = :'clone_kind'
      AND source_snapshot_id = :'source_snapshot_id'
      AND release_id = :'release_id'
      AND destroyed_at IS NULL
      AND created_at >= CURRENT_TIMESTAMP - INTERVAL '30 days'
      AND expires_at > CURRENT_TIMESTAMP
      AND (
        (baseline_completed_at IS NULL AND evidence_seal_sha256 IS NULL)
        OR
        (baseline_completed_at IS NOT NULL
         AND evidence_seal_sha256 ~ '^[0-9a-f]{64}$')
      )
      AND (
        (e2e_before_recorded_at IS NULL
         AND e2e_before_evidence_seal_sha256 IS NULL)
        OR
        (e2e_before_recorded_at IS NOT NULL
         AND e2e_before_evidence_seal_sha256 ~ '^[0-9a-f]{64}$')
      )
  ) THEN 'missing-or-expired-sentinel'
  ELSE 'ok'
END;
SQL
  )" || dbops_die "sentinel query failed; no operation was executed"

  [[ "$result" == "ok" ]] || dbops_die "database sentinel validation returned ${result}"
}

dbops_evidence_dir() {
  dbops_require EVIDENCE_DIR
  [[ "${EVIDENCE_DIR}" != "/" && "${EVIDENCE_DIR}" != "." ]] ||
    dbops_die "EVIDENCE_DIR must be a dedicated directory"
  [[ ! -L "${EVIDENCE_DIR}" ]] || dbops_die "EVIDENCE_DIR must not be a symbolic link"
  [[ ! -e "${EVIDENCE_DIR}/EVIDENCE-SEAL" \
      && ! -e "${EVIDENCE_DIR}/EVIDENCE-MANIFEST.sha256" ]] ||
    dbops_die "evidence root is sealed or has a partial seal; append is forbidden"
  umask 077
  mkdir -p "${EVIDENCE_DIR}"
}

dbops_create_fresh_evidence_dir() {
  dbops_require EVIDENCE_DIR
  [[ "${EVIDENCE_DIR}" != "/" && "${EVIDENCE_DIR}" != "." ]] ||
    dbops_die "EVIDENCE_DIR must be a dedicated directory"
  [[ ! -e "${EVIDENCE_DIR}" && ! -L "${EVIDENCE_DIR}" ]] ||
    dbops_die "EVIDENCE_DIR already exists; use a fresh dedicated evidence directory"
  umask 077
  mkdir -p "${EVIDENCE_DIR}"
}

dbops_assert_artifacts_absent() {
  local artifact
  for artifact in "$@"; do
    [[ ! -e "${artifact}" && ! -L "${artifact}" ]] ||
      dbops_die "refusing to overwrite existing evidence artifact ${artifact}"
  done
}
