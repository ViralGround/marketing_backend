#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Keep a distinct operator entry point so a sanitized run cannot accidentally
# reuse the exact-clone confirmation phrase. The shared guarded implementation
# still performs the allowlist, production refusal, sentinel, and one-shot checks.
if [[ "${CLONE_KIND:-}" != "sanitized" ]]; then
  printf 'REFUSED: migrate-sanitized-clone.sh requires CLONE_KIND=sanitized\n' >&2
  exit 64
fi

exec bash "${SCRIPT_DIR}/migrate-exact-clone.sh"
