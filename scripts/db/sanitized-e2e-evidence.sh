#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/clone_guard.sh
source "${SCRIPT_DIR}/lib/clone_guard.sh"

[[ "${CLONE_KIND}" == "sanitized" ]] || dbops_die "E2E evidence requires CLONE_KIND=sanitized"
dbops_verify_sentinel
bash "${SCRIPT_DIR}/assert-sanitized-e2e-target.sh"
dbops_require E2E_EVIDENCE_PHASE
case "${E2E_EVIDENCE_PHASE}" in before|after) ;; *) dbops_die "E2E_EVIDENCE_PHASE must be before or after" ;; esac
if [[ "${E2E_EVIDENCE_PHASE}" == "before" ]]; then
  dbops_require E2E_ATTESTATION_PGUSER
  dbops_require E2E_ATTESTATION_PGPASSWORD
  [[ "${E2E_ATTESTATION_PGUSER}" =~ ^[A-Za-z0-9][A-Za-z0-9_.-]*$ ]] ||
    dbops_die "E2E_ATTESTATION_PGUSER must be a plain role identifier"
  [[ "${E2E_ATTESTATION_PGUSER}" != "${PGUSER}" ]] ||
    dbops_die "E2E attestation requires a dedicated role separate from the evidence reader"
  [[ "${E2E_BEFORE_ATTESTATION_CONFIRMATION:-}" == \
      "ATTEST_IMMUTABLE_SANITIZED_E2E_BEFORE_ONCE" ]] ||
    dbops_die "E2E_BEFORE_ATTESTATION_CONFIRMATION does not match the required phrase"
fi
dbops_require SYNTHETIC_MEMBER_IDS
[[ "${SYNTHETIC_MEMBER_IDS}" =~ ^[0-9]+(,[0-9]+)*$ ]] ||
  dbops_die "SYNTHETIC_MEMBER_IDS must be a non-empty comma-separated integer list"
synthetic_contact_ids="${SYNTHETIC_CONTACT_IDS:-}"
if [[ -n "$synthetic_contact_ids" && ! "$synthetic_contact_ids" =~ ^[0-9]+(,[0-9]+)*$ ]]; then
  dbops_die "SYNTHETIC_CONTACT_IDS must be empty or a comma-separated integer list"
fi
before_evidence_dir=""
before_evidence_seal_sha256=""
if [[ "${E2E_EVIDENCE_PHASE}" == "after" ]]; then
  dbops_require E2E_BEFORE_EVIDENCE_DIR
  dbops_require CLONE_E2E_BEFORE_EVIDENCE_SEAL_SHA256
  [[ "${CLONE_E2E_BEFORE_EVIDENCE_SEAL_SHA256}" =~ ^[0-9a-f]{64}$ ]] ||
    dbops_die "CLONE_E2E_BEFORE_EVIDENCE_SEAL_SHA256 must be lowercase 64-hex"
  before_evidence_dir="${E2E_BEFORE_EVIDENCE_DIR}"
  before_evidence_seal_sha256="${CLONE_E2E_BEFORE_EVIDENCE_SEAL_SHA256}"
  [[ "${before_evidence_dir}" != "${EVIDENCE_DIR}" ]] ||
    dbops_die "after evidence must use a fresh root separate from sealed before evidence"
  bash "${SCRIPT_DIR}/assert-sanitized-e2e-before-attestation.sh"
else
  before_slot_state="$(PGOPTIONS='-c default_transaction_read_only=on -c statement_timeout=10000 -c lock_timeout=3000' \
    dbops_psql --quiet --tuples-only --no-align \
      --set=sentinel_id="${CLONE_SENTINEL_ID}" \
      --set=source_snapshot_id="${SOURCE_SNAPSHOT_ID}" \
      --set=release_id="${RELEASE_ID}" <<'SQL'
SELECT CASE
  WHEN e2e_before_evidence_seal_sha256 IS NULL AND e2e_before_recorded_at IS NULL
    THEN 'available'
  ELSE 'already-attested'
END
FROM preprod_guard.clone_sentinel
WHERE sentinel_id = :'sentinel_id'
  AND clone_kind = 'sanitized'
  AND source_snapshot_id = :'source_snapshot_id'
  AND release_id = :'release_id'
  AND destroyed_at IS NULL
  AND expires_at > CURRENT_TIMESTAMP;
SQL
)" || dbops_die "before-E2E one-shot slot query failed"
  [[ "$before_slot_state" == "available" ]] ||
    dbops_die "before-E2E evidence was already attested; never replace or rerun it"
fi
dbops_create_fresh_evidence_dir

# Re-attest the exact latest application schema into each final E2E evidence
# root. This catches any unclassified table/column introduced during staging.
PUBLIC_SCHEMA_ALLOWLIST_MODE=latest-exact \
  bash "${SCRIPT_DIR}/assert-public-schema-allowlist.sh"

member_array="{${SYNTHETIC_MEMBER_IDS}}"
contact_array="{${synthetic_contact_ids}}"
allowlist_file="${EVIDENCE_DIR}/sanitized-e2e-allowlist.txt"
provenance_report="${EVIDENCE_DIR}/sanitized-e2e-synthetic-provenance.tsv"
current_allowlist="memberIds=${SYNTHETIC_MEMBER_IDS}\ncontactIds=${synthetic_contact_ids}\n"

if [[ "${E2E_EVIDENCE_PHASE}" == "before" ]]; then
  dbops_assert_artifacts_absent \
    "$allowlist_file" \
    "${EVIDENCE_DIR}/sanitized-e2e-allowlist.sha256" \
    "$provenance_report" \
    "${provenance_report}.sha256" \
    "${EVIDENCE_DIR}/sanitized-e2e-before.tsv" \
    "${EVIDENCE_DIR}/sanitized-e2e-before.tsv.sha256"
  printf '%b' "$current_allowlist" >"${allowlist_file}"
  (
    cd "${EVIDENCE_DIR}"
    sha256sum sanitized-e2e-allowlist.txt >sanitized-e2e-allowlist.sha256
  )
else
  before_allowlist_file="${before_evidence_dir}/sanitized-e2e-allowlist.txt"
  [[ -f "$before_allowlist_file" ]] || dbops_die "missing immutable E2E allowlist from before phase"
  [[ "$(cat "$before_allowlist_file")" == "$(printf '%b' "$current_allowlist")" ]] ||
    dbops_die "after-phase synthetic allowlist differs from the before phase"
  (
    cd "${before_evidence_dir}"
    sha256sum --check sanitized-e2e-allowlist.sha256 >/dev/null
  ) ||
    dbops_die "E2E allowlist checksum changed"
  printf '%b' "$current_allowlist" >"${allowlist_file}"
  (
    cd "${EVIDENCE_DIR}"
    sha256sum sanitized-e2e-allowlist.txt >sanitized-e2e-allowlist.sha256
  )
  before_manifest_sha256="$(sha256sum \
    "${before_evidence_dir}/EVIDENCE-MANIFEST.sha256" | awk '{print $1}')"
  cat >"${EVIDENCE_DIR}/evidence-parent-chain.tsv" <<EOF
format\tviralground-sanitized-e2e-chain-v1
releaseId\t${RELEASE_ID}
sentinelId\t${CLONE_SENTINEL_ID}
sourceSnapshotIdSha256\t$(printf '%s' "${SOURCE_SNAPSHOT_ID}" | sha256sum | awk '{print $1}')
beforeEvidenceSealSha256\t${before_evidence_seal_sha256}
beforeManifestSha256\t${before_manifest_sha256}
EOF
  (
    cd "${EVIDENCE_DIR}"
    sha256sum evidence-parent-chain.tsv >evidence-parent-chain.sha256
  )
fi

if [[ "${E2E_EVIDENCE_PHASE}" == "before" ]]; then
  # IDs alone are not authority to exempt a row from the before/after fingerprint.
  # Prove, using counts only, that every exempt member/contact was created after
  # this clone sentinel through the supported staging creation paths. CREATOR and
  # COMPANY rows require their public-signup audit plus transactional consent/profile
  # graph. The single ADMIN is the documented one-shot bootstrap exception.
  PGOPTIONS='-c default_transaction_read_only=on -c statement_timeout=60000 -c lock_timeout=3000' \
    dbops_psql --quiet --tuples-only --no-align --field-separator=$'\t' \
    --set=sentinel_id="${CLONE_SENTINEL_ID}" \
    --set=source_snapshot_id="${SOURCE_SNAPSHOT_ID}" \
    --set=release_id="${RELEASE_ID}" \
    --set=member_ids="$member_array" \
    --set=contact_ids="$contact_array" >"${provenance_report}" <<'SQL'
WITH requested_members AS (
  SELECT member_id
  FROM unnest(:'member_ids'::integer[]) AS requested(member_id)
), distinct_members AS (
  SELECT DISTINCT member_id FROM requested_members
), requested_contacts AS (
  SELECT contact_id
  FROM unnest(:'contact_ids'::integer[]) AS requested(contact_id)
), distinct_contacts AS (
  SELECT DISTINCT contact_id FROM requested_contacts
), sentinel AS (
  SELECT created_at
  FROM preprod_guard.clone_sentinel
  WHERE sentinel_id = :'sentinel_id'
    AND clone_kind = 'sanitized'
    AND source_snapshot_id = :'source_snapshot_id'
    AND release_id = :'release_id'
    AND destroyed_at IS NULL
    AND expires_at > CURRENT_TIMESTAMP
), allowed_members AS (
  SELECT member.*
  FROM members member
  JOIN distinct_members requested ON requested.member_id = member.id
), allowed_contacts AS (
  SELECT contact.*
  FROM contact_requests contact
  JOIN distinct_contacts requested ON requested.contact_id = contact.id
)
SELECT check_name, violation_count
FROM (
  SELECT 'live_sentinel_count_not_one' AS check_name,
         ((SELECT COUNT(*) FROM sentinel) <> 1)::int::bigint AS violation_count
  UNION ALL
  SELECT 'duplicate_member_allowlist_id',
         (SELECT COUNT(*) FROM requested_members)
           - (SELECT COUNT(*) FROM distinct_members) AS violation_count
  UNION ALL
  SELECT 'missing_member_allowlist_row',
         (SELECT COUNT(*) FROM distinct_members)
           - (SELECT COUNT(*) FROM allowed_members)
  UNION ALL
  SELECT 'unsupported_member_role', COUNT(*)::bigint
  FROM allowed_members WHERE role NOT IN ('CREATOR', 'COMPANY', 'ADMIN')
  UNION ALL
  SELECT 'source_sanitized_member_marker', COUNT(*)::bigint
  FROM allowed_members
  WHERE email = 'member+' || id || '@example.invalid'
     OR password = '!SANITIZED-DISABLED!'
     OR name = 'Sanitized Member ' || id
  UNION ALL
  SELECT 'public_member_without_post_sentinel_signup_audit', COUNT(*)::bigint
  FROM allowed_members member, sentinel clone
  WHERE member.role IN ('CREATOR', 'COMPANY')
    AND NOT EXISTS (
      SELECT 1
      FROM audit_logs audit
      WHERE audit.actor_id = member.id
        AND audit.actor_role = member.role
        AND audit.action = 'MEMBER_SIGNUP'
        AND audit.resource_type = 'member'
        AND audit.resource_id = member.id::text
        AND audit.outcome = 'SUCCESS'
        AND audit.created_at >= clone.created_at
    )
  UNION ALL
  SELECT 'creator_without_single_profile', COUNT(*)::bigint
  FROM allowed_members member
  WHERE member.role = 'CREATOR'
    AND (SELECT COUNT(*) FROM creator_profiles profile
         WHERE profile.member_id = member.id) <> 1
  UNION ALL
  SELECT 'company_without_single_profile', COUNT(*)::bigint
  FROM allowed_members member
  WHERE member.role = 'COMPANY'
    AND (SELECT COUNT(*) FROM company_profiles profile
         WHERE profile.member_id = member.id) <> 1
  UNION ALL
  SELECT 'public_member_missing_post_sentinel_consent', COUNT(*)::bigint
  FROM allowed_members member
  CROSS JOIN sentinel clone
  CROSS JOIN LATERAL (
    SELECT consent_type
    FROM (VALUES
      ('TERMS_OF_SERVICE'),
      ('PRIVACY_POLICY'),
      ('AGE_14_CONFIRMATION'),
      ('CREATOR_THIRD_PARTY_PROVISION')
    ) required(consent_type)
    WHERE member.role = 'CREATOR'
       OR (member.role = 'COMPANY'
           AND consent_type <> 'CREATOR_THIRD_PARTY_PROVISION')
  ) required_consent
  WHERE member.role IN ('CREATOR', 'COMPANY')
    AND NOT EXISTS (
      SELECT 1
      FROM member_consent_evidence evidence
      WHERE evidence.member_id = member.id
        AND evidence.consent_type = required_consent.consent_type
        AND evidence.agreed_at >= clone.created_at
    )
  UNION ALL
  SELECT 'bootstrap_admin_count_not_one',
         (COUNT(*) FILTER (WHERE role = 'ADMIN') <> 1)::int::bigint
  FROM allowed_members
  UNION ALL
  SELECT 'invalid_bootstrap_admin_provenance', COUNT(*)::bigint
  FROM allowed_members member, sentinel clone
  WHERE member.role = 'ADMIN'
    AND (member.status <> 'APPROVED'
      OR member.email_verified IS DISTINCT FROM TRUE
      OR member.password = '!SANITIZED-DISABLED!'
      OR member.created_at < clone.created_at AT TIME ZONE current_setting('TimeZone')
      OR EXISTS (SELECT 1 FROM creator_profiles profile WHERE profile.member_id = member.id)
      OR EXISTS (SELECT 1 FROM company_profiles profile WHERE profile.member_id = member.id))
  UNION ALL
  SELECT 'duplicate_contact_allowlist_id',
         (SELECT COUNT(*) FROM requested_contacts)
           - (SELECT COUNT(*) FROM distinct_contacts)
  UNION ALL
  SELECT 'missing_contact_allowlist_row',
         (SELECT COUNT(*) FROM distinct_contacts)
           - (SELECT COUNT(*) FROM allowed_contacts)
  UNION ALL
  SELECT 'source_sanitized_contact_marker', COUNT(*)::bigint
  FROM allowed_contacts
  WHERE email = 'contact+' || id || '@example.invalid'
     OR brand_name = 'Sanitized Contact Brand ' || id
  UNION ALL
  SELECT 'contact_without_post_sentinel_public_api_audit', COUNT(*)::bigint
  FROM allowed_contacts contact, sentinel clone
  WHERE contact.privacy_consent_version IS NULL
     OR contact.privacy_consented_at IS NULL
     OR contact.privacy_consented_at < clone.created_at
     OR NOT EXISTS (
       SELECT 1
       FROM audit_logs audit
       WHERE audit.actor_id IS NULL
         AND audit.action = 'CONTACT_RECEIVED'
         AND audit.resource_type = 'contactRequest'
         AND audit.resource_id = contact.id::text
         AND audit.outcome = 'SUCCESS'
         AND audit.created_at >= clone.created_at
     )
) checks
ORDER BY check_name;
SQL
  provenance_violations="$(awk -F '\t' '{ total += $2 } END { print total + 0 }' \
    "${provenance_report}")"
  (
    cd "${EVIDENCE_DIR}"
    sha256sum "$(basename "${provenance_report}")" \
      >"$(basename "${provenance_report}.sha256")"
  )
  [[ "$provenance_violations" == "0" ]] ||
    dbops_die "synthetic allowlist provenance has ${provenance_violations} violations"
fi

report="${EVIDENCE_DIR}/sanitized-e2e-${E2E_EVIDENCE_PHASE}.tsv"
relationship_report="${EVIDENCE_DIR}/sanitized-e2e-${E2E_EVIDENCE_PHASE}-relationship-invariants.tsv"
if [[ "${E2E_EVIDENCE_PHASE}" == "after" ]]; then
  dbops_assert_artifacts_absent \
    "$report" "${report}.sha256" "$relationship_report" "${relationship_report}.sha256" \
    "${EVIDENCE_DIR}/sanitization-verification-e2e-after.tsv" \
    "${EVIDENCE_DIR}/sanitization-verification-e2e-after.sha256"
else
  dbops_assert_artifacts_absent "$relationship_report" "${relationship_report}.sha256"
fi

# A row is excluded from the durable fingerprint only when every member endpoint
# in that relationship is synthetic. Mixed synthetic/source-snapshot relations
# are always a release blocker and can never disappear behind the allowlist.
PGOPTIONS='-c default_transaction_read_only=on -c statement_timeout=300000 -c lock_timeout=3000' \
  dbops_psql --quiet --tuples-only --no-align --field-separator=$'\t' \
  --set=member_ids="$member_array" >"${relationship_report}" <<'SQL'
WITH allowed AS (SELECT :'member_ids'::integer[] AS member_ids)
SELECT check_name, violation_count
FROM (
  SELECT 'mixed_campaign_application_members' AS check_name, COUNT(*)::bigint AS violation_count
  FROM campaign_applications ca
  JOIN campaigns c ON c.id = ca.campaign_id, allowed a
  WHERE COALESCE(ca.creator_id = ANY(a.member_ids), FALSE)
     <> COALESCE(c.created_by_id = ANY(a.member_ids), FALSE)
  UNION ALL
  SELECT 'mixed_review_members', COUNT(*)::bigint
  FROM reviews r
  JOIN campaign_applications ca ON ca.id = r.application_id
  JOIN campaigns c ON c.id = ca.campaign_id, allowed a
  WHERE (COALESCE((r.author_id = ANY(a.member_ids))::int, 0)
       + COALESCE((r.target_id = ANY(a.member_ids))::int, 0)
       + COALESCE((ca.creator_id = ANY(a.member_ids))::int, 0)
       + COALESCE((c.created_by_id = ANY(a.member_ids))::int, 0)) BETWEEN 1 AND 3
) checks
ORDER BY check_name;
SQL
relationship_violations="$(awk -F '\t' '{ total += $2 } END { print total + 0 }' \
  "${relationship_report}")"
(
  cd "${EVIDENCE_DIR}"
  sha256sum "$(basename "${relationship_report}")" \
    >"$(basename "${relationship_report}.sha256")"
)
[[ "$relationship_violations" == "0" ]] ||
  dbops_die "synthetic/source-snapshot relationship invariant has ${relationship_violations} violations"

PGOPTIONS='-c default_transaction_read_only=on -c statement_timeout=300000 -c lock_timeout=3000' \
  dbops_psql --quiet --tuples-only --no-align --field-separator=$'\t' \
  --set=member_ids="$member_array" --set=contact_ids="$contact_array" >"${report}" <<'SQL'
WITH allowed AS (
  SELECT :'member_ids'::integer[] AS member_ids,
         :'contact_ids'::integer[] AS contact_ids
)
SELECT table_name, row_count, row_fingerprint
FROM (
  SELECT 'members' AS table_name, COUNT(*)::bigint AS row_count,
         md5(COALESCE(string_agg(md5(row_to_json(x)::text), '' ORDER BY x.id), '')) AS row_fingerprint
  FROM (SELECT m.* FROM members m, allowed a WHERE NOT (m.id = ANY(a.member_ids))) x
  UNION ALL
  SELECT 'creator_profiles', COUNT(*), md5(COALESCE(string_agg(md5(row_to_json(x)::text), '' ORDER BY x.id), ''))
  FROM (SELECT p.* FROM creator_profiles p, allowed a WHERE NOT (p.member_id = ANY(a.member_ids))) x
  UNION ALL
  SELECT 'company_profiles', COUNT(*), md5(COALESCE(string_agg(md5(row_to_json(x)::text), '' ORDER BY x.id), ''))
  FROM (SELECT p.* FROM company_profiles p, allowed a WHERE NOT (p.member_id = ANY(a.member_ids))) x
  UNION ALL
  SELECT 'campaigns', COUNT(*), md5(COALESCE(string_agg(md5(row_to_json(x)::text), '' ORDER BY x.id), ''))
  FROM (SELECT c.* FROM campaigns c, allowed a WHERE NOT (c.created_by_id = ANY(a.member_ids))) x
  UNION ALL
  SELECT 'campaign_applications', COUNT(*), md5(COALESCE(string_agg(md5(row_to_json(x)::text), '' ORDER BY x.id), ''))
  FROM (
    SELECT ca.* FROM campaign_applications ca
    JOIN campaigns c ON c.id = ca.campaign_id, allowed a
    WHERE NOT ((ca.creator_id = ANY(a.member_ids)) AND (c.created_by_id = ANY(a.member_ids)))
  ) x
  UNION ALL
  SELECT 'application_submissions', COUNT(*), md5(COALESCE(string_agg(md5(row_to_json(x)::text), '' ORDER BY x.id), ''))
  FROM (
    SELECT s.* FROM application_submissions s
    JOIN campaign_applications ca ON ca.id = s.application_id
    JOIN campaigns c ON c.id = ca.campaign_id, allowed a
    WHERE NOT ((ca.creator_id = ANY(a.member_ids)) AND (c.created_by_id = ANY(a.member_ids)))
  ) x
  UNION ALL
  SELECT 'reviews', COUNT(*), md5(COALESCE(string_agg(md5(row_to_json(x)::text), '' ORDER BY x.id), ''))
  FROM (
    SELECT r.* FROM reviews r
    JOIN campaign_applications ca ON ca.id = r.application_id
    JOIN campaigns c ON c.id = ca.campaign_id, allowed a
    WHERE NOT ((r.author_id = ANY(a.member_ids)) AND (r.target_id = ANY(a.member_ids))
      AND (ca.creator_id = ANY(a.member_ids)) AND (c.created_by_id = ANY(a.member_ids)))
  ) x
  UNION ALL
  SELECT 'submission_metrics', COUNT(*), md5(COALESCE(string_agg(md5(row_to_json(x)::text), '' ORDER BY x.id), ''))
  FROM (
    SELECT sm.* FROM submission_metrics sm
    JOIN campaign_applications ca ON ca.id = sm.application_id
    JOIN campaigns c ON c.id = ca.campaign_id, allowed a
    WHERE NOT ((ca.creator_id = ANY(a.member_ids)) AND (c.created_by_id = ANY(a.member_ids)))
  ) x
  UNION ALL
  SELECT 'reel_metric_snapshots', COUNT(*), md5(COALESCE(string_agg(md5(row_to_json(x)::text), '' ORDER BY x.id), ''))
  FROM (
    SELECT rm.* FROM reel_metric_snapshots rm
    JOIN campaign_applications ca ON ca.id = rm.application_id
    JOIN campaigns c ON c.id = ca.campaign_id, allowed a
    WHERE NOT ((ca.creator_id = ANY(a.member_ids)) AND (c.created_by_id = ANY(a.member_ids)))
  ) x
  UNION ALL
  SELECT 'creator_instagram_connections', COUNT(*), md5(COALESCE(string_agg(md5(row_to_json(x)::text), '' ORDER BY x.id), ''))
  FROM (
    SELECT ic.* FROM creator_instagram_connections ic, allowed a
    WHERE NOT (ic.creator_id = ANY(a.member_ids))
  ) x
  UNION ALL
  SELECT 'escrow_transactions', COUNT(*), md5(COALESCE(string_agg(md5(row_to_json(x)::text), '' ORDER BY x.id), ''))
  FROM (
    SELECT et.* FROM escrow_transactions et
    JOIN campaigns c ON c.id = et.campaign_id, allowed a
    WHERE NOT (c.created_by_id = ANY(a.member_ids))
  ) x
  UNION ALL
  SELECT 'payment_ledger_entries', COUNT(*), md5(COALESCE(string_agg(md5(row_to_json(x)::text), '' ORDER BY x.id), ''))
  FROM (
    SELECT le.* FROM payment_ledger_entries le
    JOIN campaigns c ON c.id = le.campaign_id, allowed a
    WHERE NOT (c.created_by_id = ANY(a.member_ids))
  ) x
  UNION ALL
  SELECT 'payment_webhook_events', COUNT(*), md5(COALESCE(string_agg(md5(row_to_json(x)::text), '' ORDER BY x.id), ''))
  FROM (SELECT pwe.* FROM payment_webhook_events pwe) x
  UNION ALL
  SELECT 'member_consent_evidence', COUNT(*), md5(COALESCE(string_agg(md5(row_to_json(x)::text), '' ORDER BY x.id), ''))
  FROM (
    SELECT ce.* FROM member_consent_evidence ce, allowed a
    WHERE NOT (ce.member_id = ANY(a.member_ids))
  ) x
  UNION ALL
  SELECT 'marketing_consent_events', COUNT(*), md5(COALESCE(string_agg(md5(row_to_json(x)::text), '' ORDER BY x.id), ''))
  FROM (
    SELECT me.* FROM marketing_consent_events me, allowed a
    WHERE NOT (me.member_id = ANY(a.member_ids))
  ) x
  UNION ALL
  SELECT 'contact_requests', COUNT(*), md5(COALESCE(string_agg(md5(row_to_json(x)::text), '' ORDER BY x.id), ''))
  FROM (
    SELECT cr.* FROM contact_requests cr, allowed a
    WHERE NOT (cr.id = ANY(a.contact_ids))
  ) x
  UNION ALL
  SELECT 'upload_records', COUNT(*), md5(COALESCE(string_agg(md5(row_to_json(x)::text), '' ORDER BY x.file_key), ''))
  FROM (
    SELECT ur.* FROM upload_records ur, allowed a
    WHERE NOT (ur.owner_id = ANY(a.member_ids))
  ) x
  UNION ALL
  SELECT 'audit_logs', COUNT(*), md5(COALESCE(string_agg(md5(row_to_json(x)::text), '' ORDER BY x.id), ''))
  FROM (
    SELECT al.* FROM audit_logs al, allowed a
    WHERE al.actor_id IS NULL OR NOT (al.actor_id = ANY(a.member_ids))
  ) x
) fingerprints
ORDER BY table_name;
SQL

(
  cd "${EVIDENCE_DIR}"
  sha256sum "$(basename "$report")" >"$(basename "${report}.sha256")"
)
cat "$report"

if [[ "${E2E_EVIDENCE_PHASE}" == "after" ]]; then
  before="${before_evidence_dir}/sanitized-e2e-before.tsv"
  [[ -f "$before" ]] || dbops_die "missing before-E2E business fingerprint"
  cmp -s "$before" "$report" || dbops_die "non-synthetic business rows changed during E2E"
  comparison_report="${EVIDENCE_DIR}/sanitized-e2e-comparison.tsv"
  comparison_checksum="${comparison_report}.sha256"
  dbops_assert_artifacts_absent "$comparison_report" "$comparison_checksum"
  before_fingerprint_sha256="$(sha256sum "$before" | awk '{print $1}')"
  after_fingerprint_sha256="$(sha256sum "$report" | awk '{print $1}')"
  [[ "$before_fingerprint_sha256" == "$after_fingerprint_sha256" ]] ||
    dbops_die "sanitized E2E before/after fingerprint digests differ after byte comparison"
  cat >"$comparison_report" <<EOF
format	viralground-sanitized-e2e-comparison-v1
releaseId	${RELEASE_ID}
sourceSnapshotIdSha256	$(printf '%s' "${SOURCE_SNAPSHOT_ID}" | sha256sum | awk '{print $1}')
beforeEvidenceSealSha256	${before_evidence_seal_sha256}
beforeFingerprintSha256	${before_fingerprint_sha256}
afterFingerprintSha256	${after_fingerprint_sha256}
result	MATCHED
EOF
  sha256sum "$comparison_report" >"$comparison_checksum"
  SANITIZATION_EVIDENCE_LABEL=e2e-after bash "${SCRIPT_DIR}/verify-sanitization.sh"
  EVIDENCE_STAGE=sanitized-e2e-after bash "${SCRIPT_DIR}/seal-evidence.sh"
  after_seal_sha256="$(sha256sum "${EVIDENCE_DIR}/EVIDENCE-SEAL" | awk '{print $1}')"
  [[ "$after_seal_sha256" =~ ^[0-9a-f]{64}$ ]] ||
    dbops_die "after evidence seal SHA-256 is invalid"
  printf 'Non-synthetic business rows are unchanged and sensitive-data counts are zero; sanitizedE2eAfterSealSha256=%s.\n' \
    "$after_seal_sha256"
else
  attestation_report="${EVIDENCE_DIR}/e2e-attestor-role-safety.tsv"
  E2E_ATTESTATION_PGUSER="${E2E_ATTESTATION_PGUSER}" \
    E2E_ATTESTATION_PGPASSWORD="${E2E_ATTESTATION_PGPASSWORD}" \
    PGUSER="${E2E_ATTESTATION_PGUSER}" PGPASSWORD="${E2E_ATTESTATION_PGPASSWORD}" \
    PGOPTIONS='-c default_transaction_read_only=on -c statement_timeout=10000 -c lock_timeout=3000' \
    dbops_psql --quiet --tuples-only --no-align --field-separator=$'\t' \
      --set=expected_role="${E2E_ATTESTATION_PGUSER}" >"${attestation_report}" <<'SQL'
SELECT check_name, violation_count
FROM (
  SELECT 'wrong_current_user' AS check_name,
         (current_user <> :'expected_role')::int::bigint AS violation_count
  UNION ALL SELECT 'wrong_session_user',
         (session_user <> :'expected_role')::int::bigint
  UNION ALL SELECT 'role_has_elevated_attributes',
         (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls)::int::bigint
  FROM pg_roles WHERE rolname = current_user
  UNION ALL SELECT 'database_create_or_temp_privilege',
         (has_database_privilege(current_user, current_database(), 'CREATE')
          OR has_database_privilege(current_user, current_database(), 'TEMP'))::int::bigint
  UNION ALL SELECT 'non_guard_schema_create_privilege', COUNT(*)::bigint
  FROM pg_namespace namespace
  WHERE namespace.nspname <> 'information_schema'
    AND namespace.nspname <> 'preprod_guard'
    AND namespace.nspname !~ '^pg_'
    AND has_schema_privilege(current_user, namespace.oid, 'CREATE')
  UNION ALL SELECT 'guard_schema_create_privilege',
         has_schema_privilege(current_user, 'preprod_guard', 'CREATE')::int::bigint
  UNION ALL SELECT 'missing_guard_schema_usage',
         (NOT has_schema_privilege(current_user, 'preprod_guard', 'USAGE'))::int::bigint
  UNION ALL SELECT 'application_table_privilege', COUNT(*)::bigint
  FROM pg_class relation
  JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
  WHERE relation.relkind IN ('r', 'p', 'v', 'm', 'f')
    AND namespace.nspname <> 'information_schema'
    AND namespace.nspname <> 'preprod_guard'
    AND namespace.nspname !~ '^pg_'
    AND has_table_privilege(
      current_user, relation.oid,
      'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')
  UNION ALL SELECT 'application_column_privilege', COUNT(*)::bigint
  FROM pg_class relation
  JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
  WHERE relation.relkind IN ('r', 'p', 'v', 'm', 'f')
    AND namespace.nspname <> 'information_schema'
    AND namespace.nspname <> 'preprod_guard'
    AND namespace.nspname !~ '^pg_'
    AND has_any_column_privilege(current_user, relation.oid, 'SELECT,INSERT,UPDATE,REFERENCES')
  UNION ALL SELECT 'application_sequence_privilege', COUNT(*)::bigint
  FROM pg_class sequence
  JOIN pg_namespace namespace ON namespace.oid = sequence.relnamespace
  WHERE sequence.relkind = 'S'
    AND namespace.nspname <> 'information_schema'
    AND namespace.nspname !~ '^pg_'
    AND has_sequence_privilege(current_user, sequence.oid, 'SELECT,USAGE,UPDATE')
  UNION ALL SELECT 'executable_security_definer_path', COUNT(*)::bigint
  FROM pg_proc routine
  JOIN pg_namespace namespace ON namespace.oid = routine.pronamespace
  WHERE routine.prosecdef
    AND namespace.nspname <> 'information_schema'
    AND namespace.nspname !~ '^pg_'
    AND has_function_privilege(current_user, routine.oid, 'EXECUTE')
  UNION ALL SELECT 'unexpected_guard_table_privilege', COUNT(*)::bigint
  FROM pg_class relation
  JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
  WHERE relation.relkind IN ('r', 'p', 'v', 'm', 'f')
    AND namespace.nspname = 'preprod_guard'
    AND ((relation.relname = 'clone_sentinel'
          AND has_table_privilege(current_user, relation.oid,
                                  'INSERT,UPDATE,DELETE,TRUNCATE'))
      OR (relation.relname <> 'clone_sentinel'
          AND has_table_privilege(current_user, relation.oid,
                                  'SELECT,INSERT,UPDATE,DELETE,TRUNCATE')))
  UNION ALL SELECT 'missing_sentinel_select',
         (NOT has_table_privilege(current_user,
             'preprod_guard.clone_sentinel', 'SELECT'))::int::bigint
  UNION ALL SELECT 'unexpected_sentinel_column_update', COUNT(*)::bigint
  FROM information_schema.columns column_value
  WHERE column_value.table_schema = 'preprod_guard'
    AND column_value.table_name = 'clone_sentinel'
    AND column_value.column_name NOT IN (
      'e2e_before_evidence_seal_sha256', 'e2e_before_recorded_at')
    AND has_column_privilege(current_user, 'preprod_guard.clone_sentinel',
                             column_value.column_name, 'UPDATE')
  UNION ALL SELECT 'missing_required_sentinel_column_update',
         ((NOT has_column_privilege(current_user,
             'preprod_guard.clone_sentinel', 'e2e_before_evidence_seal_sha256', 'UPDATE'))
          OR (NOT has_column_privilege(current_user,
             'preprod_guard.clone_sentinel', 'e2e_before_recorded_at', 'UPDATE')))::int::bigint
  UNION ALL SELECT 'set_role_membership', COUNT(*)::bigint
  FROM pg_auth_members membership
  JOIN pg_roles member_role ON member_role.oid = membership.member
  WHERE member_role.rolname = session_user
) checks
ORDER BY check_name;
SQL
  attestor_violations="$(awk -F '\t' '{ total += $2 } END { print total + 0 }' \
    "${attestation_report}")"
  (
    cd "${EVIDENCE_DIR}"
    sha256sum "$(basename "${attestation_report}")" \
      >"$(basename "${attestation_report}.sha256")"
  )
  [[ "$attestor_violations" == "0" ]] ||
    dbops_die "E2E evidence attestor role safety gate found ${attestor_violations} violations"

  EVIDENCE_STAGE=sanitized-e2e-before bash "${SCRIPT_DIR}/seal-evidence.sh"
  before_seal_sha256="$(sha256sum "${EVIDENCE_DIR}/EVIDENCE-SEAL" | awk '{print $1}')"
  [[ "$before_seal_sha256" =~ ^[0-9a-f]{64}$ ]] ||
    dbops_die "before evidence seal SHA-256 is invalid"

  attested="$(PGUSER="${E2E_ATTESTATION_PGUSER}" \
    PGPASSWORD="${E2E_ATTESTATION_PGPASSWORD}" \
    PGOPTIONS='-c statement_timeout=10000 -c lock_timeout=3000' \
    dbops_psql --quiet --tuples-only --no-align \
      --set=sentinel_id="${CLONE_SENTINEL_ID}" \
      --set=source_snapshot_id="${SOURCE_SNAPSHOT_ID}" \
      --set=release_id="${RELEASE_ID}" \
      --set=migration_seal_sha256="${CLONE_EVIDENCE_SEAL_SHA256}" \
      --set=before_seal_sha256="${before_seal_sha256}" <<'SQL'
UPDATE preprod_guard.clone_sentinel
SET e2e_before_evidence_seal_sha256 = :'before_seal_sha256',
    e2e_before_recorded_at = CURRENT_TIMESTAMP
WHERE sentinel_id = :'sentinel_id'
  AND clone_kind = 'sanitized'
  AND source_snapshot_id = :'source_snapshot_id'
  AND release_id = :'release_id'
  AND destroyed_at IS NULL
  AND expires_at > CURRENT_TIMESTAMP
  AND baseline_started_at IS NOT NULL
  AND baseline_completed_at IS NOT NULL
  AND evidence_seal_sha256 = :'migration_seal_sha256'
  AND e2e_before_evidence_seal_sha256 IS NULL
  AND e2e_before_recorded_at IS NULL
RETURNING e2e_before_evidence_seal_sha256;
SQL
)"
  [[ "$attested" == "$before_seal_sha256" ]] ||
    dbops_die "could not atomically bind before-E2E evidence to the clone sentinel"
  printf 'Captured and attested immutable pre-E2E business fingerprint; CLONE_E2E_BEFORE_EVIDENCE_SEAL_SHA256=%s.\n' \
    "$before_seal_sha256"
fi
