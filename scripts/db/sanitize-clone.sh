#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/clone_guard.sh
source "${SCRIPT_DIR}/lib/clone_guard.sh"

dbops_verify_sentinel
[[ "${CLONE_KIND}" == "sanitized" ]] || dbops_die "sanitization is permitted only for CLONE_KIND=sanitized"
[[ "${SANITIZE_CONFIRMATION:-}" == "ERASE_PII_ON_DISPOSABLE_SANITIZED_CLONE" ]] ||
  dbops_die "SANITIZE_CONFIRMATION does not match the required phrase"

# Fail before the first UPDATE/TRUNCATE if the restored public schema contains
# any table or column that the current sanitizer does not explicitly classify.
bash "${SCRIPT_DIR}/assert-public-schema-allowlist.sh"

dbops_psql --quiet <<'SQL'
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '15min';
SELECT pg_advisory_xact_lock(816724913, 20260822);

DO $$
DECLARE other_sessions integer;
BEGIN
  SELECT COUNT(*) INTO other_sessions
  FROM pg_stat_activity
  WHERE datname = current_database() AND pid <> pg_backend_pid();
  IF other_sessions <> 0 THEN
    RAISE EXCEPTION 'sanitization requires exclusive database access; other sessions=%', other_sessions;
  END IF;
END
$$;

-- Append-only protection remains mandatory in runtime. It is suspended only in
-- this transaction so copied production text and external identifiers can be scrubbed.
-- The conditional form supports both a legacy V1-shaped clone and a migrated clone.
DO $$
DECLARE table_name text;
BEGIN
  FOREACH table_name IN ARRAY ARRAY[
    'audit_logs', 'escrow_transactions', 'payment_ledger_entries', 'payment_webhook_events'
  ] LOOP
    IF to_regclass('public.' || table_name) IS NOT NULL THEN
      EXECUTE format('ALTER TABLE public.%I DISABLE TRIGGER USER', table_name);
    END IF;
  END LOOP;
END
$$;

UPDATE members
SET email = 'member+' || id || '@example.invalid',
    password = '!SANITIZED-DISABLED!',
    name = 'Sanitized Member ' || id,
    email_verified = FALSE;

UPDATE creator_profiles
SET profile_image = NULL,
    instagram_id = NULL,
    tiktok_id = NULL,
    youtube_id = NULL;

DO $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = 'public' AND table_name = 'creator_profiles'
      AND column_name = 'public_profile_opt_in'
  ) THEN
    EXECUTE 'UPDATE creator_profiles SET public_profile_opt_in = FALSE, public_profile_consented_at = NULL';
  END IF;
END
$$;

UPDATE company_profiles
SET company_name = 'Sanitized Company ' || id,
    business_number = lpad((id::bigint % 10000000000)::text, 10, '0'),
    representative_name = 'Representative ' || id,
    contact_name = 'Contact ' || id,
    contact_phone = '000-0000-' || lpad((id::bigint % 10000)::text, 4, '0'),
    address = NULL,
    homepage = NULL,
    introduction = NULL,
    logo_file_key = NULL;

UPDATE campaigns
SET title = 'Sanitized Campaign ' || id,
    description = 'Sanitized campaign fixture',
    brand_name = 'Sanitized Brand ' || id,
    brand_introduction = NULL,
    brand_logo_file_key = NULL,
    thumbnail_url = NULL,
    thumbnail_file_key = NULL,
    requirements = NULL;

UPDATE campaign_applications
SET message = NULL,
    submission_url = NULL,
    video_file_key = NULL,
    video_content_type = NULL,
    video_size_bytes = NULL,
    review_comment = NULL;

UPDATE application_submissions
SET video_file_key = NULL,
    video_content_type = NULL,
    video_size_bytes = NULL,
    submission_url = NULL,
    review_comment = NULL;

UPDATE reviews SET comment = NULL;
UPDATE submission_metrics SET external_url = NULL;

UPDATE creator_instagram_connections
SET provider_user_id = NULL,
    provider_account_id = NULL,
    ig_username = NULL,
    last_error = NULL,
    status = 'DISCONNECTED',
    connected_at = NULL,
    last_synced_at = NULL;

DO $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = 'public' AND table_name = 'creator_instagram_connections'
      AND column_name = 'encrypted_access_token'
  ) THEN
    EXECUTE 'UPDATE creator_instagram_connections
             SET encrypted_access_token = NULL,
                 access_token_expires_at = NULL,
                 token_refreshed_at = NULL';
  END IF;
END
$$;

UPDATE contact_requests
SET email = 'contact+' || id || '@example.invalid',
    brand_name = 'Sanitized Contact Brand ' || id,
    contact_name = NULL;

UPDATE escrow_transactions SET memo = NULL;

DO $$
BEGIN
  IF to_regclass('public.audit_logs') IS NOT NULL THEN
    EXECUTE 'UPDATE audit_logs SET request_id = NULL, resource_id = NULL, reason = NULL';
  END IF;
  IF EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = 'public' AND table_name = 'escrow_transactions'
      AND column_name = 'provider_tx_id'
  ) THEN
    EXECUTE $scrub$
      UPDATE escrow_transactions
      SET provider_tx_id = 'SANITIZED-' || id,
          reason = 'sanitized clone fixture'
    $scrub$;
  END IF;
  IF to_regclass('public.payment_webhook_events') IS NOT NULL THEN
    EXECUTE $scrub$
      UPDATE payment_webhook_events
      SET provider_event_id = 'sanitized-event-' || id,
          provider_object_id = NULL,
          payload_sha256 = md5('sanitized:' || id) || md5('sanitized-payload:' || id)
    $scrub$;
  END IF;
END
$$;

TRUNCATE TABLE email_verification_codes;

DO $$
DECLARE table_name text;
BEGIN
  FOREACH table_name IN ARRAY ARRAY[
    'refresh_tokens', 'password_reset_codes', 'instagram_oauth_states',
    'instagram_webhook_deliveries', 'upload_records', 'notification_outbox'
  ] LOOP
    IF to_regclass('public.' || table_name) IS NOT NULL THEN
      EXECUTE format('TRUNCATE TABLE public.%I', table_name);
    END IF;
  END LOOP;
END
$$;

DO $$
DECLARE table_name text;
BEGIN
  FOREACH table_name IN ARRAY ARRAY[
    'audit_logs', 'escrow_transactions', 'payment_ledger_entries', 'payment_webhook_events'
  ] LOOP
    IF to_regclass('public.' || table_name) IS NOT NULL THEN
      EXECUTE format('ALTER TABLE public.%I ENABLE TRIGGER USER', table_name);
    END IF;
  END LOOP;
END
$$;

COMMIT;
SQL

printf 'Sanitized clone transaction committed. Run verify-sanitization.sh immediately.\n'
