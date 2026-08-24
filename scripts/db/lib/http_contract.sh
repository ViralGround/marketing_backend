#!/usr/bin/env bash

# Compare the immutable response-contract records produced by the RC and legacy
# backends. The caller decides how to turn a mismatch into its release-specific
# refusal; this helper always writes a human-readable comparison artifact.
compare_http_contract_evidence() {
  local compatibility_tsv="$1"
  local comparison_tsv="$2"
  local python_bin="${DBOPS_PYTHON_BIN:-python3}"
  command -v "$python_bin" >/dev/null 2>&1 || {
    printf 'Python interpreter not found: %s\n' "$python_bin" >&2
    return 127
  }
  "$python_bin" - "$compatibility_tsv" "$comparison_tsv" <<'PY'
import csv
import re
import sys
from pathlib import Path

source_path = Path(sys.argv[1])
output_path = Path(sys.argv[2])
digest_pattern = re.compile(r"^[0-9a-f]{64}$")
rows = {}

with source_path.open("r", encoding="utf-8", newline="") as stream:
    for row_number, row in enumerate(csv.reader(stream, delimiter="\t"), start=1):
        if not row:
            continue
        label = row[0]
        if label not in {"rc", "legacy"}:
            # Footer evidence is appended only after this comparison. Refuse any
            # unexpected pre-comparison row instead of silently ignoring it.
            raise SystemExit(f"unexpected HTTP contract label on row {row_number}: {label!r}")
        if label in rows:
            raise SystemExit(f"duplicate HTTP contract row for {label}")
        if len(row) != 6 or row[1] != "started-hibernate-validated-readonly-temp-auth":
            raise SystemExit(f"malformed HTTP contract row for {label}")
        contracts = {}
        for expected_endpoint, value in zip(
                ("landing", "loginInvalid", "loginSuccess", "refreshSuccess"), row[2:]):
            parts = value.split(":", 2)
            if len(parts) != 3 or parts[0] != expected_endpoint:
                raise SystemExit(
                    f"malformed {expected_endpoint} HTTP contract for {label}")
            status, shape_digest = parts[1:]
            if not status.isdigit() or not digest_pattern.fullmatch(shape_digest):
                raise SystemExit(
                    f"malformed {expected_endpoint} status/shape digest for {label}")
            contracts[expected_endpoint] = (status, shape_digest)
        rows[label] = contracts

if set(rows) != {"rc", "legacy"}:
    raise SystemExit("both rc and legacy HTTP contract rows are required")

comparison_rows = []
mismatches = []
for endpoint in ("landing", "loginInvalid", "loginSuccess", "refreshSuccess"):
    rc_status, rc_shape = rows["rc"][endpoint]
    legacy_status, legacy_shape = rows["legacy"][endpoint]
    result = "MATCHED" if (rc_status, rc_shape) == (legacy_status, legacy_shape) else "MISMATCH"
    comparison_rows.append([
        endpoint,
        rc_status,
        rc_shape,
        legacy_status,
        legacy_shape,
        result,
    ])
    if result == "MISMATCH":
        mismatches.append(endpoint)

temporary_path = output_path.with_name(f".{output_path.name}.tmp")
with temporary_path.open("w", encoding="utf-8", newline="") as stream:
    writer = csv.writer(stream, delimiter="\t", lineterminator="\n")
    writer.writerow([
        "endpoint", "rcStatus", "rcShapeSha256",
        "legacyStatus", "legacyShapeSha256", "result",
    ])
    writer.writerows(comparison_rows)
temporary_path.replace(output_path)

if mismatches:
    raise SystemExit(
        "RC/legacy HTTP contract mismatch: " + ", ".join(mismatches))
PY
}
