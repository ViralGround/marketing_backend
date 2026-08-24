#!/usr/bin/env bash
set -Eeuo pipefail

die() {
  printf 'REFUSED: %s\n' "$*" >&2
  exit 64
}

[[ -n "${EVIDENCE_DIR:-}" ]] || die "EVIDENCE_DIR is required"
[[ -d "${EVIDENCE_DIR}" && ! -L "${EVIDENCE_DIR}" ]] ||
  die "sealed EVIDENCE_DIR must be an existing non-symlink directory"
manifest="${EVIDENCE_DIR}/EVIDENCE-MANIFEST.sha256"
manifest_checksum="${EVIDENCE_DIR}/EVIDENCE-MANIFEST.sha256.sha256"
seal="${EVIDENCE_DIR}/EVIDENCE-SEAL"
seal_checksum="${EVIDENCE_DIR}/EVIDENCE-SEAL.sha256"
for artifact in "$manifest" "$manifest_checksum" "$seal" "$seal_checksum"; do
  [[ -f "$artifact" && ! -L "$artifact" ]] || die "missing evidence seal artifact ${artifact}"
done
[[ -z "$(find "${EVIDENCE_DIR}" -type l -print -quit)" ]] ||
  die "sealed evidence contains a symbolic link"

(cd "${EVIDENCE_DIR}" && sha256sum --check EVIDENCE-MANIFEST.sha256 >/dev/null) ||
  die "evidence manifest content verification failed"
(cd "${EVIDENCE_DIR}" && \
  sha256sum --check EVIDENCE-MANIFEST.sha256.sha256 >/dev/null) ||
  die "evidence manifest checksum verification failed"
(cd "${EVIDENCE_DIR}" && sha256sum --check EVIDENCE-SEAL.sha256 >/dev/null) ||
  die "evidence seal checksum verification failed"

manifest_hash="$(sha256sum "$manifest" | awk '{print $1}')"
grep -Fxq 'format=viralground-evidence-seal-v1' "$seal" || die "unknown evidence seal format"
grep -Fxq "manifestSha256=${manifest_hash}" "$seal" || die "seal/manifest hash mismatch"
sealed_stage="$(sed -n 's/^stage=//p' "$seal")"
[[ "$sealed_stage" =~ ^[a-z0-9]+(-[a-z0-9]+)*$ ]] || die "invalid sealed evidence stage"
case "$sealed_stage" in
  exact-*|sanitized-*)
    [[ -n "${SOURCE_SNAPSHOT_ID:-}" ]] ||
      die "SOURCE_SNAPSHOT_ID is required to verify clone evidence"
    [[ "${SOURCE_SNAPSHOT_ID}" =~ ^[A-Za-z0-9][A-Za-z0-9._:/@+-]{0,255}$ ]] ||
      die "SOURCE_SNAPSHOT_ID is invalid"
    expected_source_snapshot_hash="$(printf '%s' "${SOURCE_SNAPSHOT_ID}" | sha256sum | awk '{print $1}')"
    grep -Fxq "sourceSnapshotIdSha256=${expected_source_snapshot_hash}" "$seal" ||
      die "sealed evidence source snapshot mismatch"
    ;;
esac
if [[ -n "${RELEASE_ID:-}" ]]; then
  grep -Fxq "releaseId=${RELEASE_ID}" "$seal" || die "sealed evidence release ID mismatch"
fi
if [[ -n "${EVIDENCE_STAGE:-}" ]]; then
  grep -Fxq "stage=${EVIDENCE_STAGE}" "$seal" || die "sealed evidence stage mismatch"
fi

actual_paths="$(mktemp)"
manifest_paths="$(mktemp)"
cleanup() {
  rm -f -- "$actual_paths" "$manifest_paths"
}
trap cleanup EXIT
(
  cd "${EVIDENCE_DIR}"
  find . -type f \
    ! -path './EVIDENCE-MANIFEST.sha256' \
    ! -path './EVIDENCE-MANIFEST.sha256.sha256' \
    ! -path './EVIDENCE-SEAL' \
    ! -path './EVIDENCE-SEAL.sha256' \
    -print | LC_ALL=C sort
) >"$actual_paths"
sed -E 's/^[0-9a-f]{64} [ *]//' "$manifest" | LC_ALL=C sort >"$manifest_paths"
cmp -s "$actual_paths" "$manifest_paths" ||
  die "sealed evidence has an appended, removed, or unlisted artifact"

expected_count="$(grep -E '^artifactCount=[0-9]+$' "$seal" | cut -d= -f2 || true)"
actual_count="$(wc -l <"$manifest_paths" | tr -d ' ')"
[[ -n "$expected_count" && "$expected_count" == "$actual_count" ]] ||
  die "sealed evidence artifact count mismatch"

cleanup
trap - EXIT
printf 'Evidence seal verified: stage=%s artifacts=%s manifest=%s\n' \
  "${EVIDENCE_STAGE:-unspecified}" "$actual_count" "$manifest_hash"
