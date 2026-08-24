#!/usr/bin/env bash
set -Eeuo pipefail

die() {
  printf 'SUPPLY-CHAIN REFUSED: %s\n' "$*" >&2
  exit 1
}

wrapper='gradle/wrapper/gradle-wrapper.properties'
[[ "$(grep -Ec '^distributionSha256Sum=[0-9a-f]{64}$' "$wrapper")" == "1" ]] ||
  die 'Gradle wrapper must contain exactly one lowercase 64-hex distributionSha256Sum'
[[ "$(grep -Ec '^distributionSha256Sum=' "$wrapper")" == "1" ]] ||
  die 'Gradle wrapper contains duplicate checksum declarations'

dockerfile_images=0
while IFS= read -r line; do
  [[ "$line" =~ ^[[:space:]]*FROM[[:space:]]+([^[:space:]]+) ]] || continue
  image="${BASH_REMATCH[1]}"
  [[ "$image" =~ @sha256:[0-9a-f]{64}$ ]] ||
    die "Dockerfile FROM is not digest-pinned: ${image}"
  dockerfile_images=$((dockerfile_images + 1))
done <Dockerfile
(( dockerfile_images > 0 )) || die 'Dockerfile has no FROM instruction to verify'

compose_images=0
while IFS= read -r line; do
  [[ "$line" =~ ^[[:space:]]*image:[[:space:]]+([^[:space:]#]+) ]] || continue
  image="${BASH_REMATCH[1]%\"}"
  image="${image#\"}"
  image="${image%\'}"
  image="${image#\'}"
  [[ "$image" =~ @sha256:[0-9a-f]{64}$ ]] ||
    die "Compose image is not digest-pinned: ${image}"
  compose_images=$((compose_images + 1))
done <compose.local-preprod.yml
(( compose_images > 0 )) || die 'Compose file has no image reference to verify'

mapfile -t testcontainer_refs < <(
  grep -RhoE '(postgres:[^"[:space:]]+|minio/minio:[^"[:space:]]+)' \
    src/test/java | LC_ALL=C sort -u
)
(( ${#testcontainer_refs[@]} >= 2 )) ||
  die 'expected PostgreSQL and MinIO Testcontainers image references'
postgres_seen=false
minio_seen=false
for image in "${testcontainer_refs[@]}"; do
  [[ "$image" =~ @sha256:[0-9a-f]{64}$ ]] ||
    die "Testcontainers image is not digest-pinned: ${image}"
  [[ "$image" == postgres:* ]] && postgres_seen=true
  [[ "$image" == minio/minio:* ]] && minio_seen=true
done
[[ "$postgres_seen" == "true" && "$minio_seen" == "true" ]] ||
  die 'both PostgreSQL and MinIO Testcontainers pins are required'

printf 'Immutable supply-chain references verified: Dockerfile=%s Compose=%s Testcontainers=%s.\n' \
  "$dockerfile_images" "$compose_images" "${#testcontainer_refs[@]}"
