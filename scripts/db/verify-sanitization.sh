#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/clone_guard.sh
source "${SCRIPT_DIR}/lib/clone_guard.sh"

dbops_verify_sentinel
dbops_evidence_dir
[[ "${CLONE_KIND}" == "sanitized" ]] || dbops_die "verification requires CLONE_KIND=sanitized"

evidence_label="${SANITIZATION_EVIDENCE_LABEL:-migration}"
[[ "$evidence_label" =~ ^[a-z0-9]+(-[a-z0-9]+)*$ ]] ||
  dbops_die "SANITIZATION_EVIDENCE_LABEL must be a lowercase slug"
if [[ "$evidence_label" == "migration" ]]; then
  evidence_stem="sanitization-verification"
else
  evidence_stem="sanitization-verification-${evidence_label}"
fi
report="${EVIDENCE_DIR}/${evidence_stem}.tsv"
checksum="${EVIDENCE_DIR}/${evidence_stem}.sha256"
dbops_assert_artifacts_absent "$report" "$checksum"
PGOPTIONS='-c default_transaction_read_only=on -c statement_timeout=60000 -c lock_timeout=3000' \
  dbops_psql --quiet --tuples-only --no-align --field-separator=$'\t' >"${report}" <<'SQL'
SELECT check_name, remaining_count
FROM (
  SELECT 'member_email_or_login_credential' AS check_name, COUNT(*)::bigint AS remaining_count
  FROM members
  WHERE email !~ '^member\+[0-9]+@example[.]invalid$'
     OR password <> '!SANITIZED-DISABLED!'
     OR name <> 'Sanitized Member ' || id
  UNION ALL
  SELECT 'creator_social_or_profile_identifier', COUNT(*)
  FROM creator_profiles
  WHERE profile_image IS NOT NULL OR instagram_id IS NOT NULL
     OR tiktok_id IS NOT NULL OR youtube_id IS NOT NULL
     OR public_profile_opt_in = TRUE OR public_profile_consented_at IS NOT NULL
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
  SELECT 'meta_identifier_or_token', COUNT(*)
  FROM creator_instagram_connections
  WHERE provider_user_id IS NOT NULL OR provider_account_id IS NOT NULL
     OR ig_username IS NOT NULL OR encrypted_access_token IS NOT NULL OR last_error IS NOT NULL
     OR access_token_expires_at IS NOT NULL OR token_refreshed_at IS NOT NULL
     OR connected_at IS NOT NULL OR last_synced_at IS NOT NULL OR status <> 'DISCONNECTED'
  UNION ALL
  SELECT 'contact_identity', COUNT(*)
  FROM contact_requests
  WHERE email !~ '^contact\+[0-9]+@example[.]invalid$'
     OR brand_name <> 'Sanitized Contact Brand ' || id OR contact_name IS NOT NULL
  UNION ALL
  SELECT 'audit_free_text', COUNT(*)
  FROM audit_logs WHERE request_id IS NOT NULL OR resource_id IS NOT NULL OR reason IS NOT NULL
  UNION ALL
  SELECT 'escrow_external_identifier_or_text', COUNT(*)
  FROM escrow_transactions
  WHERE memo IS NOT NULL OR provider_tx_id <> 'SANITIZED-' || id
     OR reason <> 'sanitized clone fixture'
  UNION ALL
  SELECT 'payment_webhook_external_identifier', COUNT(*)
  FROM payment_webhook_events
  WHERE provider_event_id <> 'sanitized-event-' || id OR provider_object_id IS NOT NULL
     OR payload_sha256 <> md5('sanitized:' || id) || md5('sanitized-payload:' || id)
  UNION ALL
  SELECT 'active_refresh_token', COUNT(*) FROM refresh_tokens
  UNION ALL
  SELECT 'email_verification_secret', COUNT(*) FROM email_verification_codes
  UNION ALL
  SELECT 'password_reset_secret', COUNT(*) FROM password_reset_codes
  UNION ALL
  SELECT 'oauth_state_secret', COUNT(*) FROM instagram_oauth_states
  UNION ALL
  SELECT 'webhook_delivery_identifier', COUNT(*) FROM instagram_webhook_deliveries
  UNION ALL
  SELECT 'production_file_key', COUNT(*) FROM upload_records
  UNION ALL
  SELECT 'notification_outbox_content', COUNT(*) FROM notification_outbox
) checks
ORDER BY check_name;
SQL

cat "${report}"
remaining="$(awk -F '\t' '{ total += $2 } END { print total + 0 }' "${report}")"
sha256sum "${report}" >"${checksum}"
[[ "$remaining" == "0" ]] || dbops_die "sanitization verification found ${remaining} remaining sensitive rows"

printf 'Sanitization verification passed. Evidence: %s\n' "${report}"
