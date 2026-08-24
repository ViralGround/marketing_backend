#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GUARD="${SCRIPT_DIR}/../lib/clone_guard.sh"

parent="$(mktemp -d)"
fresh="${parent}/fresh-evidence"
EVIDENCE_DIR="$fresh" bash -c 'source "$1"; dbops_create_fresh_evidence_dir' _ "$GUARD"
[[ -d "$fresh" ]] || {
  printf 'fresh evidence directory was not created\n' >&2
  exit 1
}

printf 'immutable-marker\n' >"${fresh}/marker.txt"
set +e
output="$(EVIDENCE_DIR="$fresh" bash -c \
  'source "$1"; dbops_create_fresh_evidence_dir' _ "$GUARD" 2>&1)"
status=$?
set -e

[[ "$status" == "64" ]] || {
  printf 'expected overwrite refusal exit 64, got %s\n' "$status" >&2
  exit 1
}
grep -Fq 'REFUSED: EVIDENCE_DIR already exists' <<<"$output" || {
  printf 'expected immutable evidence refusal message\n' >&2
  exit 1
}
[[ "$(cat "${fresh}/marker.txt")" == "immutable-marker" ]] || {
  printf 'existing evidence marker was changed\n' >&2
  exit 1
}

printf 'Evidence directory overwrite refusal passed.\n'
