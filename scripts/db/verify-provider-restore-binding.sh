#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/clone_guard.sh
source "${SCRIPT_DIR}/lib/clone_guard.sh"

for name in RELEASE_ID SOURCE_SNAPSHOT_ID \
  EXACT_PROVIDER_RESTORE_RECEIPT SANITIZED_PROVIDER_RESTORE_RECEIPT \
  EXACT_SOURCE_ORIGIN_FINGERPRINT SANITIZED_SOURCE_ORIGIN_FINGERPRINT \
  EXACT_CLONE_HOST EXACT_CLONE_DATABASE SANITIZED_CLONE_HOST SANITIZED_CLONE_DATABASE \
  EVIDENCE_DIR; do
  dbops_require "$name"
done
[[ "${PROVIDER_RESTORE_BINDING_CONFIRMATION:-}" == \
    "VERIFY_PROVIDER_NATIVE_RESTORE_BINDING_ONLY" ]] ||
  dbops_die "PROVIDER_RESTORE_BINDING_CONFIRMATION does not match the required phrase"
dbops_create_fresh_evidence_dir

for artifact in \
  "$EXACT_PROVIDER_RESTORE_RECEIPT" "$SANITIZED_PROVIDER_RESTORE_RECEIPT" \
  "$EXACT_SOURCE_ORIGIN_FINGERPRINT" "$SANITIZED_SOURCE_ORIGIN_FINGERPRINT"; do
  [[ -f "$artifact" && ! -L "$artifact" ]] ||
    dbops_die "restore-binding input must be a regular non-symlink file: ${artifact}"
done
python_bin="${DBOPS_PYTHON_BIN:-python3}"
command -v "$python_bin" >/dev/null 2>&1 ||
  dbops_die "Python interpreter is required (DBOPS_PYTHON_BIN=${python_bin})"
"$python_bin" --version >/dev/null 2>&1 ||
  dbops_die "configured Python interpreter is not executable: ${python_bin}"

report="${EVIDENCE_DIR}/provider-restore-binding.tsv"
"$python_bin" - \
  "$EXACT_PROVIDER_RESTORE_RECEIPT" "$SANITIZED_PROVIDER_RESTORE_RECEIPT" \
  "$EXACT_SOURCE_ORIGIN_FINGERPRINT" "$SANITIZED_SOURCE_ORIGIN_FINGERPRINT" \
  "$SOURCE_SNAPSHOT_ID" "$RELEASE_ID" \
  "$EXACT_CLONE_HOST" "$EXACT_CLONE_DATABASE" \
  "$SANITIZED_CLONE_HOST" "$SANITIZED_CLONE_DATABASE" >"$report" <<'PY'
import hashlib
import json
import re
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

(exact_receipt_path, sanitized_receipt_path, exact_fingerprint_path,
 sanitized_fingerprint_path, source_snapshot_id, release_id,
 exact_host, exact_database, sanitized_host, sanitized_database) = sys.argv[1:]

safe_id = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:/@+-]{0,255}$")
safe_host = re.compile(r"^[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?[.]?$")
safe_database = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_-]*$")
if not safe_id.fullmatch(source_snapshot_id):
    raise SystemExit("invalid protected source snapshot ID")

def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()

def unique_object(pairs):
    value = {}
    for key, item in pairs:
        if key in value:
            raise ValueError(f"duplicate JSON key: {key}")
        value[key] = item
    return value

def load_receipt(path, kind, host, database):
    try:
        value = json.loads(Path(path).read_text(encoding="utf-8"),
                           object_pairs_hook=unique_object)
    except (json.JSONDecodeError, ValueError) as error:
        raise SystemExit(f"{kind} provider receipt is invalid or has duplicate fields") from error
    required = {
        "schemaVersion", "provider", "providerOperationId", "cloneKind",
        "sourceSnapshotId", "targetHost", "targetDatabase", "status",
        "restoreStartedAtUtc", "restoreCompletedAtUtc", "releaseId",
    }
    if set(value) != required:
        raise SystemExit(f"{kind} provider receipt has an unexpected or missing field set")
    if value["schemaVersion"] != 1 or value["cloneKind"] != kind:
        raise SystemExit(f"{kind} provider receipt schema/clone kind mismatch")
    if value["sourceSnapshotId"] != source_snapshot_id or value["releaseId"] != release_id:
        raise SystemExit(f"{kind} provider receipt source/release mismatch")
    if value["targetHost"].rstrip(".").lower() != host.rstrip(".").lower():
        raise SystemExit(f"{kind} provider receipt target host mismatch")
    if value["targetDatabase"] != database:
        raise SystemExit(f"{kind} provider receipt target database mismatch")
    if value["status"] != "SUCCEEDED":
        raise SystemExit(f"{kind} provider restore did not succeed")
    if not isinstance(value["provider"], str) or not re.fullmatch(r"[a-z0-9][a-z0-9-]{1,63}", value["provider"]):
        raise SystemExit(f"{kind} provider is invalid")
    if not isinstance(value["providerOperationId"], str) or not safe_id.fullmatch(value["providerOperationId"]):
        raise SystemExit(f"{kind} provider operation ID is invalid")
    if not safe_host.fullmatch(value["targetHost"]) or not safe_database.fullmatch(value["targetDatabase"]):
        raise SystemExit(f"{kind} provider target is malformed")
    try:
        started = datetime.fromisoformat(value["restoreStartedAtUtc"].replace("Z", "+00:00"))
        completed = datetime.fromisoformat(value["restoreCompletedAtUtc"].replace("Z", "+00:00"))
    except ValueError as error:
        raise SystemExit(f"{kind} restore timing is invalid") from error
    if (started.tzinfo is None or completed.tzinfo is None
            or not value["restoreStartedAtUtc"].endswith("Z")
            or not value["restoreCompletedAtUtc"].endswith("Z")):
        raise SystemExit(f"{kind} restore timing must be UTC")
    if completed < started or completed - started > timedelta(hours=24):
        raise SystemExit(f"{kind} restore timing is reversed or exceeds 24 hours")
    if completed > datetime.now(timezone.utc) + timedelta(minutes=5):
        raise SystemExit(f"{kind} restore completion is in the future")
    return value

def load_origin(path, label):
    raw = Path(path).read_bytes()
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError as error:
        raise SystemExit(f"{label} origin fingerprint is not UTF-8") from error
    if not text.endswith("\n") or "\r" in text or any(
            ord(character) < 32 and character not in {"\n", "\t"}
            for character in text):
        raise SystemExit(f"{label} origin fingerprint contains control bytes or lacks final newline")
    lines = text[:-1].split("\n")
    if not lines or "" in lines or lines != sorted(lines) or len(lines) != len(set(lines)):
        raise SystemExit(f"{label} origin fingerprint must be sorted, nonempty, and unique")

    structural = 0
    row_counts = set()
    financial = set()
    relation_pattern = re.compile(r"^[a-z_][a-z0-9_]*[.][a-z_][a-z0-9_]*$")
    integer_pattern = re.compile(r"^(?:0|[1-9][0-9]*)$")
    financial_pattern = re.compile(
        r"^(?:0|[1-9][0-9]*):-?(?:0|[1-9][0-9]*):-?(?:0|[1-9][0-9]*)$")
    for line in lines:
        fields = line.split("\t")
        if len(fields) != 2:
            raise SystemExit(f"{label} origin fingerprint has a malformed row")
        key, value = fields
        if key == "schema-structural-md5":
            structural += 1
            if structural != 1 or not re.fullmatch(r"[0-9a-f]{32}", value):
                raise SystemExit(f"{label} origin structural digest is malformed or duplicated")
        elif key.startswith("row-count|"):
            relation = key.removeprefix("row-count|")
            if (not relation_pattern.fullmatch(relation)
                    or not integer_pattern.fullmatch(value) or relation in row_counts):
                raise SystemExit(f"{label} origin row-count is malformed or duplicated")
            row_counts.add(relation)
        elif key in {"financial|campaigns", "financial|escrow_transactions"}:
            if not financial_pattern.fullmatch(value) or key in financial:
                raise SystemExit(f"{label} origin financial aggregate is malformed or duplicated")
            financial.add(key)
        else:
            raise SystemExit(f"{label} origin fingerprint has an unsupported key")
    if (structural != 1 or "public.members" not in row_counts
            or financial != {"financial|campaigns", "financial|escrow_transactions"}):
        raise SystemExit(f"{label} origin fingerprint is missing required aggregate rows")
    return raw

exact = load_receipt(exact_receipt_path, "exact", exact_host, exact_database)
sanitized = load_receipt(sanitized_receipt_path, "sanitized", sanitized_host, sanitized_database)
if exact["provider"] != sanitized["provider"]:
    raise SystemExit("exact/sanitized provider mismatch")
if exact["providerOperationId"] == sanitized["providerOperationId"]:
    raise SystemExit("exact/sanitized restores must have distinct provider operation IDs")

exact_origin = load_origin(exact_fingerprint_path, "exact")
sanitized_origin = load_origin(sanitized_fingerprint_path, "sanitized")
if exact_origin != sanitized_origin:
    raise SystemExit("exact/sanitized pre-mask origin fingerprints differ")

rows = [
    ("provider", exact["provider"]),
    ("sourceSnapshotIdSha256", hashlib.sha256(source_snapshot_id.encode()).hexdigest()),
    ("exactReceiptSha256", digest(exact_receipt_path)),
    ("sanitizedReceiptSha256", digest(sanitized_receipt_path)),
    ("exactOriginFingerprintSha256", digest(exact_fingerprint_path)),
    ("sanitizedOriginFingerprintSha256", digest(sanitized_fingerprint_path)),
    ("exactRestoreCompletedAtUtc", exact["restoreCompletedAtUtc"]),
    ("sanitizedRestoreCompletedAtUtc", sanitized["restoreCompletedAtUtc"]),
    ("providerRestoreReceiptsMatched", "true"),
    ("preMaskOriginFingerprintMatched", "true"),
]
for key, value in rows:
    print(f"{key}\t{value}")
PY

sha256sum "$report" >"${report}.sha256"
EVIDENCE_STAGE=provider-restore-binding bash "${SCRIPT_DIR}/seal-evidence.sh"
printf 'Provider-native restore receipts and pre-mask source fingerprints are release-bound and matched.\n'
