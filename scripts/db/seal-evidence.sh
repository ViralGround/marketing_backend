#!/usr/bin/env bash
set -Eeuo pipefail

die() {
  printf 'REFUSED: %s\n' "$*" >&2
  exit 64
}

for name in EVIDENCE_DIR EVIDENCE_STAGE RELEASE_ID; do
  [[ -n "${!name:-}" ]] || die "required environment variable ${name} is empty"
done
[[ "${EVIDENCE_STAGE}" =~ ^[a-z0-9]+(-[a-z0-9]+)*$ ]] ||
  die "EVIDENCE_STAGE must be a lowercase slug"
[[ -d "${EVIDENCE_DIR}" && ! -L "${EVIDENCE_DIR}" ]] ||
  die "EVIDENCE_DIR must be an existing non-symlink directory"

source_snapshot_hash=""
case "${EVIDENCE_STAGE}" in
  exact-*|sanitized-*)
    [[ -n "${SOURCE_SNAPSHOT_ID:-}" ]] ||
      die "SOURCE_SNAPSHOT_ID is required for clone evidence"
    [[ "${SOURCE_SNAPSHOT_ID}" =~ ^[A-Za-z0-9][A-Za-z0-9._:/@+-]{0,255}$ ]] ||
      die "SOURCE_SNAPSHOT_ID is invalid"
    source_snapshot_hash="$(printf '%s' "${SOURCE_SNAPSHOT_ID}" | sha256sum | awk '{print $1}')"
    ;;
esac

manifest="${EVIDENCE_DIR}/EVIDENCE-MANIFEST.sha256"
manifest_checksum="${EVIDENCE_DIR}/EVIDENCE-MANIFEST.sha256.sha256"
seal="${EVIDENCE_DIR}/EVIDENCE-SEAL"
seal_checksum="${EVIDENCE_DIR}/EVIDENCE-SEAL.sha256"
for artifact in "$manifest" "$manifest_checksum" "$seal" "$seal_checksum"; do
  [[ ! -e "$artifact" && ! -L "$artifact" ]] ||
    die "evidence is already sealed or a prior seal attempt exists"
done
[[ -z "$(find "${EVIDENCE_DIR}" -type l -print -quit)" ]] ||
  die "evidence root contains a symbolic link"

umask 077
artifact_count=0
(
  cd "${EVIDENCE_DIR}"
  while IFS= read -r -d '' file; do
    [[ "$file" =~ ^[.]/[A-Za-z0-9._/-]+$ ]] ||
      die "evidence path contains unsupported characters: ${file}"
    sha256sum "$file"
  done < <(find . -type f \
    ! -path './EVIDENCE-MANIFEST.sha256' \
    ! -path './EVIDENCE-MANIFEST.sha256.sha256' \
    ! -path './EVIDENCE-SEAL' \
    ! -path './EVIDENCE-SEAL.sha256' \
    -print0 | LC_ALL=C sort -z)
) >"${manifest}"
artifact_count="$(wc -l <"${manifest}" | tr -d ' ')"
(( artifact_count > 0 )) || die "cannot seal an empty evidence root"

(
  cd "${EVIDENCE_DIR}"
  sha256sum EVIDENCE-MANIFEST.sha256 >EVIDENCE-MANIFEST.sha256.sha256
)
manifest_hash="$(sha256sum "${manifest}" | awk '{print $1}')"
cat >"${seal}" <<EOF
format=viralground-evidence-seal-v1
releaseId=${RELEASE_ID}
stage=${EVIDENCE_STAGE}
artifactCount=${artifact_count}
manifestSha256=${manifest_hash}
${source_snapshot_hash:+sourceSnapshotIdSha256=${source_snapshot_hash}}
sealedAtUtc=$(date -u +%Y-%m-%dT%H:%M:%SZ)
EOF
(
  cd "${EVIDENCE_DIR}"
  sha256sum EVIDENCE-SEAL >EVIDENCE-SEAL.sha256
)
seal_hash="$(sha256sum "${seal}" | awk '{print $1}')"
[[ "$seal_hash" =~ ^[0-9a-f]{64}$ ]] || die "evidence seal SHA-256 is invalid"

# This is an operational append barrier in addition to script-level seal checks.
# The release record must store the control-file hashes outside this directory.
find "${EVIDENCE_DIR}" -type f -exec chmod a-w {} +
find "${EVIDENCE_DIR}" -depth -type d -exec chmod a-w {} +

printf 'Evidence sealed: stage=%s artifacts=%s manifestSha256=%s evidenceSealSha256=%s\n' \
  "${EVIDENCE_STAGE}" "$artifact_count" "$manifest_hash" "$seal_hash"
