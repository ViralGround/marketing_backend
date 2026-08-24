#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DB_SCRIPT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
root="$(mktemp -d)/evidence"
mkdir -p "${root}/before"
printf 'immutable evidence\n' >"${root}/before/report.tsv"

EVIDENCE_DIR="$root" EVIDENCE_STAGE=ci-fixture RELEASE_ID=ci-seal-test \
  bash "${DB_SCRIPT_DIR}/seal-evidence.sh" >/dev/null
EVIDENCE_DIR="$root" EVIDENCE_STAGE=ci-fixture RELEASE_ID=ci-seal-test \
  bash "${DB_SCRIPT_DIR}/verify-evidence-seal.sh" >/dev/null
seal_hash="$(sha256sum "${root}/EVIDENCE-SEAL" | awk '{print $1}')"
[[ "$seal_hash" =~ ^[0-9a-f]{64}$ ]] || {
  printf 'evidence seal did not produce a lowercase 64-hex attestation\n' >&2
  exit 1
}
grep -Fq "$seal_hash" "${root}/EVIDENCE-SEAL.sha256"

set +e
output="$(EVIDENCE_DIR="$root" bash -c \
  'source "$1"; dbops_evidence_dir' _ "${DB_SCRIPT_DIR}/lib/clone_guard.sh" 2>&1)"
status=$?
set -e
[[ "$status" == "64" ]] || { printf 'sealed append guard did not refuse\n' >&2; exit 1; }
grep -Fq 'append is forbidden' <<<"$output"

chmod u+w "$root"
printf 'late append\n' >"${root}/late-artifact.tsv"
set +e
output="$(EVIDENCE_DIR="$root" EVIDENCE_STAGE=ci-fixture RELEASE_ID=ci-seal-test \
  bash "${DB_SCRIPT_DIR}/verify-evidence-seal.sh" 2>&1)"
status=$?
set -e
[[ "$status" == "64" ]] || { printf 'late append was not detected\n' >&2; exit 1; }
grep -Fq 'appended, removed, or unlisted artifact' <<<"$output"

printf 'Evidence seal and late-append refusal passed.\n'
