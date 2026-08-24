#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/clone_guard.sh
source "${SCRIPT_DIR}/lib/clone_guard.sh"

dbops_verify_sentinel
dbops_evidence_dir
[[ "${CLONE_KIND}" == "sanitized" ]] || dbops_die "verification requires CLONE_KIND=sanitized"

report="${EVIDENCE_DIR}/legacy-sanitization-verification.tsv"
checksum="${EVIDENCE_DIR}/legacy-sanitization-verification.sha256"
dbops_assert_artifacts_absent "$report" "$checksum"
PGOPTIONS='-c default_transaction_read_only=on -c statement_timeout=60000 -c lock_timeout=3000' \
  dbops_psql --quiet --tuples-only --no-align --field-separator=$'\t' >"${report}" <<'SQL'
SELECT check_name, remaining_count
FROM (
  SELECT 'member_identity_or_login_credential' AS check_name, COUNT(*)::bigint AS remaining_count
  FROM members
  WHERE email !~ '^member\+[0-9]+@example[.]invalid$'
     OR password <> '!SANITIZED-DISABLED!'
     OR name !~ '^Sanitized Member [0-9]+$'
  UNION ALL
  SELECT 'creator_social_or_profile_identifier', COUNT(*)
  FROM creator_profiles
  WHERE profile_image IS NOT NULL OR instagram_id IS NOT NULL
     OR tiktok_id IS NOT NULL OR youtube_id IS NOT NULL
  UNION ALL
  SELECT 'company_contact_or_file_data', COUNT(*)
  FROM company_profiles
  WHERE address IS NOT NULL OR homepage IS NOT NULL OR introduction IS NOT NULL
     OR logo_file_key IS NOT NULL
     OR company_name <> 'Sanitized Company ' || id
     OR business_number <> lpad((id::bigint % 10000000000)::text, 10, '0')
     OR representative_name <> 'Representative ' || id
     OR contact_name <> 'Contact ' || id
     OR contact_phone <> '000-0000-' || lpad((id::bigint % 10000)::text, 4, '0')
  UNION ALL
  SELECT 'campaign_file_or_free_text_data', COUNT(*)
  FROM campaigns
  WHERE brand_introduction IS NOT NULL OR brand_logo_file_key IS NOT NULL
     OR thumbnail_url IS NOT NULL OR thumbnail_file_key IS NOT NULL OR requirements IS NOT NULL
     OR title <> 'Sanitized Campaign ' || id
     OR description <> 'Sanitized campaign fixture'
     OR brand_name <> 'Sanitized Brand ' || id
  UNION ALL
  SELECT 'application_file_url_or_message', COUNT(*)
  FROM campaign_applications
  WHERE message IS NOT NULL OR submission_url IS NOT NULL OR video_file_key IS NOT NULL
     OR video_content_type IS NOT NULL OR video_size_bytes IS NOT NULL OR review_comment IS NOT NULL
  UNION ALL
  SELECT 'submission_file_url_or_comment', COUNT(*)
  FROM application_submissions
  WHERE video_file_key IS NOT NULL OR video_content_type IS NOT NULL OR video_size_bytes IS NOT NULL
     OR submission_url IS NOT NULL OR review_comment IS NOT NULL
  UNION ALL
  SELECT 'review_free_text', COUNT(*) FROM reviews WHERE comment IS NOT NULL
  UNION ALL
  SELECT 'metric_external_url', COUNT(*) FROM submission_metrics WHERE external_url IS NOT NULL
  UNION ALL
  SELECT 'meta_identifier', COUNT(*)
  FROM creator_instagram_connections
  WHERE provider_user_id IS NOT NULL OR provider_account_id IS NOT NULL
     OR ig_username IS NOT NULL OR last_error IS NOT NULL
     OR connected_at IS NOT NULL OR last_synced_at IS NOT NULL OR status <> 'DISCONNECTED'
  UNION ALL
  SELECT 'contact_identity', COUNT(*)
  FROM contact_requests
  WHERE email !~ '^contact\+[0-9]+@example[.]invalid$'
     OR brand_name <> 'Sanitized Contact Brand ' || id OR contact_name IS NOT NULL
  UNION ALL
  SELECT 'email_verification_secret', COUNT(*) FROM email_verification_codes
  UNION ALL
  SELECT 'escrow_memo', COUNT(*) FROM escrow_transactions WHERE memo IS NOT NULL
) checks
ORDER BY check_name;
SQL

cat "${report}"
remaining="$(awk -F '\t' '{ total += $2 } END { print total + 0 }' "${report}")"
sha256sum "${report}" >"${checksum}"
[[ "$remaining" == "0" ]] || dbops_die "legacy sanitization verification found ${remaining} remaining sensitive rows"

printf 'Legacy sanitization verification passed. Migration may now run with all integrations disabled.\n'
