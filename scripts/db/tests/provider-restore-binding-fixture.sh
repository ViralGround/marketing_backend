#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DB_SCRIPT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
fixture_root="$(mktemp -d)"
cleanup() {
  [[ "$fixture_root" == /tmp/tmp.* ]] || return 0
  chmod -R u+w "$fixture_root" 2>/dev/null || true
  rm -rf -- "$fixture_root"
}
trap cleanup EXIT

fixture_python_bin="${DBOPS_PYTHON_BIN:-python3}"
if ! command -v "$fixture_python_bin" >/dev/null 2>&1 \
    || ! "$fixture_python_bin" --version >/dev/null 2>&1; then
  if command -v python >/dev/null 2>&1 && python --version >/dev/null 2>&1; then
    fixture_python_bin=python
  else
    printf 'No executable Python interpreter is available for the fixture.\n' >&2
    exit 1
  fi
fi
export DBOPS_PYTHON_BIN="$fixture_python_bin"

write_receipt() {
  local path="$1" kind="$2" operation="$3" database="$4" snapshot="${5:-provider-snapshot-1}"
  cat >"$path" <<EOF
{"schemaVersion":1,"provider":"railway","providerOperationId":"${operation}","cloneKind":"${kind}","sourceSnapshotId":"${snapshot}","targetHost":"clone.example.test","targetDatabase":"${database}","status":"SUCCEEDED","restoreStartedAtUtc":"2026-08-22T00:30:00Z","restoreCompletedAtUtc":"2026-08-22T01:00:00Z","releaseId":"rc-20260822-fixture"}
EOF
}

write_receipt "${fixture_root}/exact.json" exact restore-exact viralground_release_exact
write_receipt "${fixture_root}/sanitized.json" sanitized restore-sanitized viralground_release_staging
cat >"${fixture_root}/exact-origin.tsv" <<'EOF'
financial|campaigns	1:100:100
financial|escrow_transactions	2:200:50
row-count|public.members	3
row-count|public.password_reset_codes	1
row-count|public.refresh_tokens	2
schema-structural-md5	0123456789abcdef0123456789abcdef
EOF
cp "${fixture_root}/exact-origin.tsv" "${fixture_root}/sanitized-origin.tsv"

common_env=(
  RELEASE_ID=rc-20260822-fixture
  SOURCE_SNAPSHOT_ID=provider-snapshot-1
  EXACT_PROVIDER_RESTORE_RECEIPT="${fixture_root}/exact.json"
  SANITIZED_PROVIDER_RESTORE_RECEIPT="${fixture_root}/sanitized.json"
  EXACT_SOURCE_ORIGIN_FINGERPRINT="${fixture_root}/exact-origin.tsv"
  SANITIZED_SOURCE_ORIGIN_FINGERPRINT="${fixture_root}/sanitized-origin.tsv"
  EXACT_CLONE_HOST=clone.example.test
  EXACT_CLONE_DATABASE=viralground_release_exact
  SANITIZED_CLONE_HOST=clone.example.test
  SANITIZED_CLONE_DATABASE=viralground_release_staging
  PROVIDER_RESTORE_BINDING_CONFIRMATION=VERIFY_PROVIDER_NATIVE_RESTORE_BINDING_ONLY
)
env "${common_env[@]}" EVIDENCE_DIR="${fixture_root}/success" \
  bash "${DB_SCRIPT_DIR}/verify-provider-restore-binding.sh" >/dev/null
grep -Fq $'providerRestoreReceiptsMatched\ttrue' \
  "${fixture_root}/success/provider-restore-binding.tsv"

sed 's/row-count|public.members\t3/row-count|public.members\t4/' \
  "${fixture_root}/exact-origin.tsv" >"${fixture_root}/sanitized-origin.tsv"
set +e
output="$(env "${common_env[@]}" EVIDENCE_DIR="${fixture_root}/origin-mismatch" \
  bash "${DB_SCRIPT_DIR}/verify-provider-restore-binding.sh" 2>&1)"
status=$?
set -e
[[ "$status" != 0 ]]
grep -Fq 'pre-mask origin fingerprints differ' <<<"$output"

cp "${fixture_root}/exact-origin.tsv" "${fixture_root}/sanitized-origin.tsv"
write_receipt "${fixture_root}/sanitized.json" sanitized restore-sanitized \
  viralground_release_staging wrong-snapshot
set +e
output="$(env "${common_env[@]}" EVIDENCE_DIR="${fixture_root}/snapshot-mismatch" \
  bash "${DB_SCRIPT_DIR}/verify-provider-restore-binding.sh" 2>&1)"
status=$?
set -e
[[ "$status" != 0 ]]
grep -Fq 'source/release mismatch' <<<"$output"

write_receipt "${fixture_root}/sanitized.json" sanitized restore-sanitized \
  viralground_release_staging

assert_origin_refusal() {
  local label="$1" expected="$2"
  set +e
  local output status
  output="$(env "${common_env[@]}" EVIDENCE_DIR="${fixture_root}/${label}" \
    bash "${DB_SCRIPT_DIR}/verify-provider-restore-binding.sh" 2>&1)"
  status=$?
  set -e
  [[ "$status" != 0 ]]
  grep -Fq "$expected" <<<"$output"
  cp "${fixture_root}/exact-origin.tsv" "${fixture_root}/sanitized-origin.tsv"
}

cp "${fixture_root}/exact-origin.tsv" "${fixture_root}/sanitized-origin.tsv"
printf 'row-value|public.refresh_tokens\tsecret-token-value\n' \
  >>"${fixture_root}/sanitized-origin.tsv"
LC_ALL=C sort -o "${fixture_root}/sanitized-origin.tsv" "${fixture_root}/sanitized-origin.tsv"
assert_origin_refusal origin-extra-key 'origin fingerprint has an unsupported key'

sed 's/row-count|public.members\t3/row-count|public.members\t-1/' \
  "${fixture_root}/exact-origin.tsv" >"${fixture_root}/sanitized-origin.tsv"
assert_origin_refusal origin-malformed-numeric 'origin row-count is malformed or duplicated'

cp "${fixture_root}/exact-origin.tsv" "${fixture_root}/sanitized-origin.tsv"
printf 'row-count|public.members\t3\n' >>"${fixture_root}/sanitized-origin.tsv"
LC_ALL=C sort -o "${fixture_root}/sanitized-origin.tsv" "${fixture_root}/sanitized-origin.tsv"
assert_origin_refusal origin-duplicate 'must be sorted, nonempty, and unique'

grep -v '^financial|escrow_transactions' "${fixture_root}/exact-origin.tsv" \
  >"${fixture_root}/sanitized-origin.tsv"
assert_origin_refusal origin-missing-required 'origin fingerprint is missing required aggregate rows'

python_bin="${DBOPS_PYTHON_BIN:-python3}"
"$python_bin" - "${fixture_root}/sanitized.json" <<'PY'
import sys
from pathlib import Path
path = Path(sys.argv[1])
text = path.read_text(encoding="utf-8")
path.write_text(text.replace('"provider":"railway"',
                             '"provider":"railway","provider":"railway"'),
                encoding="utf-8")
PY
set +e
output="$(env "${common_env[@]}" EVIDENCE_DIR="${fixture_root}/duplicate-key" \
  bash "${DB_SCRIPT_DIR}/verify-provider-restore-binding.sh" 2>&1)"
status=$?
set -e
[[ "$status" != 0 ]]
grep -Fq 'invalid or has duplicate fields' <<<"$output"

printf 'Provider restore receipt and pre-mask origin binding fixture passed.\n'
