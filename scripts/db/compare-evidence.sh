#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/clone_guard.sh
source "${SCRIPT_DIR}/lib/clone_guard.sh"

dbops_verify_sentinel
dbops_evidence_dir

before="${EVIDENCE_DIR}/before"
after="${EVIDENCE_DIR}/after"
for file in database.tsv extensions.tsv row-counts.tsv financial-aggregates.tsv \
  columns.tsv constraints.tsv indexes.tsv \
  triggers.tsv sequences.tsv sequence-integrity.tsv fk-orphans.tsv \
  flyway-history.tsv migration-files.sha256; do
  [[ -f "${before}/${file}" ]] || dbops_die "missing before evidence: ${before}/${file}"
  [[ -f "${after}/${file}" ]] || dbops_die "missing after evidence: ${after}/${file}"
done

stable_tables=(
  members creator_profiles company_profiles campaigns campaign_applications
  application_submissions email_verification_codes escrow_transactions reviews
  submission_metrics creator_instagram_connections reel_metric_snapshots contact_requests
)

report="${EVIDENCE_DIR}/before-after-comparison.tsv"
checksum="${EVIDENCE_DIR}/before-after-comparison.sha256"
dbops_assert_artifacts_absent "$report" "$checksum"
: >"${report}"
differences=0

for table in "${stable_tables[@]}"; do
  before_count="$(awk -F '\t' -v table="$table" '$1 == table { print $2 }' "${before}/row-counts.tsv")"
  after_count="$(awk -F '\t' -v table="$table" '$1 == table { print $2 }' "${after}/row-counts.tsv")"
  [[ -n "$before_count" && -n "$after_count" ]] || dbops_die "missing row count for stable table ${table}"
  result="MATCH"
  if [[ "$before_count" != "$after_count" ]]; then
    result="DIFF"
    differences=$((differences + 1))
  fi
  printf '%s\t%s\t%s\t%s\n' "$table" "$before_count" "$after_count" "$result" >>"${report}"
done

if ! cmp -s "${before}/financial-aggregates.tsv" "${after}/financial-aggregates.tsv"; then
  printf 'financial-aggregates\tsha256:%s\tsha256:%s\tDIFF\n' \
    "$(sha256sum "${before}/financial-aggregates.tsv" | awk '{print $1}')" \
    "$(sha256sum "${after}/financial-aggregates.tsv" | awk '{print $1}')" >>"${report}"
  differences=$((differences + 1))
else
  printf 'financial-aggregates\tunchanged\tunchanged\tMATCH\n' >>"${report}"
fi

before_timezone="$(awk -F '\t' '$1 == "timezone" { print $2 }' "${before}/database.tsv")"
after_timezone="$(awk -F '\t' '$1 == "timezone" { print $2 }' "${after}/database.tsv")"
[[ -n "$before_timezone" && -n "$after_timezone" ]] ||
  dbops_die "missing database timezone evidence"
if [[ "$before_timezone" != "$after_timezone" ]]; then
  printf 'database-timezone\t%s\t%s\tDIFF\n' \
    "$before_timezone" "$after_timezone" >>"${report}"
  differences=$((differences + 1))
else
  printf 'database-timezone\t%s\t%s\tMATCH\n' \
    "$before_timezone" "$after_timezone" >>"${report}"
fi

if ! cmp -s "${before}/extensions.tsv" "${after}/extensions.tsv"; then
  printf 'database-extensions\tsha256:%s\tsha256:%s\tDIFF\n' \
    "$(sha256sum "${before}/extensions.tsv" | awk '{print $1}')" \
    "$(sha256sum "${after}/extensions.tsv" | awk '{print $1}')" >>"${report}"
  differences=$((differences + 1))
else
  printf 'database-extensions\tunchanged\tunchanged\tMATCH\n' >>"${report}"
fi

for preserved_file in columns.tsv constraints.tsv indexes.tsv triggers.tsv; do
  if [[ "$preserved_file" == "constraints.tsv" ]]; then
    # V9 intentionally replaces a possible legacy members-status check with the
    # expanded WITHDRAWN-aware constraint. Every other legacy constraint is immutable.
    missing_count="$(comm -23 \
      <(awk -F '\t' '!($2 == "members" && $3 == "ck_members_status")' \
          "${before}/${preserved_file}" | LC_ALL=C sort) \
      <(LC_ALL=C sort "${after}/${preserved_file}") | wc -l | tr -d ' ')"
  else
    missing_count="$(comm -23 \
      <(LC_ALL=C sort "${before}/${preserved_file}") \
      <(LC_ALL=C sort "${after}/${preserved_file}") | wc -l | tr -d ' ')"
  fi
  if [[ "$missing_count" != "0" ]]; then
    printf 'preserved-%s\tmissing-before-objects:%s\t-\tDIFF\n' \
      "$preserved_file" "$missing_count" >>"${report}"
    differences=$((differences + 1))
  else
    printf 'preserved-%s\tall-before-objects\tretained\tMATCH\n' \
      "$preserved_file" >>"${report}"
  fi
done

if ! cmp -s "${before}/migration-files.sha256" "${after}/migration-files.sha256"; then
  printf 'repository-migration-sha\tbefore\tafter\tDIFF\n' >>"${report}"
  differences=$((differences + 1))
else
  printf 'repository-migration-sha\timmutable\timmutable\tMATCH\n' >>"${report}"
fi

unvalidated="$(awk -F '\t' '$5 != "t" { count++ } END { print count + 0 }' \
  "${after}/constraints.tsv")"
if [[ "$unvalidated" != "0" ]]; then
  printf 'validated-constraints\t-\tunvalidated:%s\tDIFF\n' "$unvalidated" >>"${report}"
  differences=$((differences + 1))
else
  printf 'validated-constraints\t-\tall-validated\tMATCH\n' >>"${report}"
fi

required_constraints=(
  'escrow_transactions:ck_escrow_amount_positive'
  'escrow_transactions:ck_escrow_balance_nonnegative'
  'members:ck_members_status'
  'members:ck_members_withdrawal_timestamp'
  'campaign_applications:ck_campaign_applications_status'
  'creator_profiles:ck_creator_public_profile_consent'
  'company_profiles:ck_company_homepage_https'
  'campaigns:ck_campaign_reward_amount'
  'campaigns:ck_campaign_max_participants'
  'campaigns:ck_campaign_total_budget'
  'members:ck_members_auth_version_nonnegative'
  'campaign_applications:ck_nonfinancial_completion'
)
for expected_constraint in "${required_constraints[@]}"; do
  table="${expected_constraint%%:*}"
  constraint="${expected_constraint#*:}"
  present="$(awk -F '\t' -v table="$table" -v constraint="$constraint" \
    '$2 == table && $3 == constraint && $5 == "t" { count++ } END { print count + 0 }' \
    "${after}/constraints.tsv")"
  if [[ "$present" != "1" ]]; then
    printf 'constraint-%s.%s\texpected-once\tpresent:%s\tDIFF\n' \
      "$table" "$constraint" "$present" >>"${report}"
    differences=$((differences + 1))
  else
    printf 'constraint-%s.%s\t-\tvalidated\tMATCH\n' \
      "$table" "$constraint" >>"${report}"
  fi
done

orphan_total="$(awk -F '\t' '{ total += $2 } END { print total + 0 }' \
  "${after}/fk-orphans.tsv")"
if [[ ! -s "${after}/fk-orphans.tsv" || "$orphan_total" != "0" ]]; then
  printf 'foreign-key-orphans\t-\tcount:%s\tDIFF\n' "$orphan_total" >>"${report}"
  differences=$((differences + 1))
else
  printf 'foreign-key-orphans\t-\tcount:0\tMATCH\n' >>"${report}"
fi

sequence_violations="$(awk -F '\t' '{ total += $4 } END { print total + 0 }' \
  "${after}/sequence-integrity.tsv")"
if [[ ! -s "${after}/sequence-integrity.tsv" || "$sequence_violations" != "0" ]]; then
  printf 'sequence-vs-primary-key\t-\tviolations:%s\tDIFF\n' \
    "$sequence_violations" >>"${report}"
  differences=$((differences + 1))
else
  printf 'sequence-vs-primary-key\t-\tviolations:0\tMATCH\n' >>"${report}"
fi

while IFS=$'\t' read -r schema sequence start min max increment cycle before_last; do
  [[ -n "$sequence" ]] || continue
  after_line="$(awk -F '\t' -v seq="$sequence" '$2 == seq { print; exit }' \
    "${after}/sequences.tsv")"
  if [[ -z "$after_line" ]]; then
    printf 'sequence-%s\tpresent\tmissing\tDIFF\n' "$sequence" >>"${report}"
    differences=$((differences + 1))
    continue
  fi
  IFS=$'\t' read -r after_schema after_sequence after_start after_min after_max \
    after_increment after_cycle after_last <<<"${after_line}"
  if [[ "$schema|$start|$min|$max|$increment|$cycle" != \
        "$after_schema|$after_start|$after_min|$after_max|$after_increment|$after_cycle" ]]; then
    printf 'sequence-%s\tdefinition-before\tdefinition-changed\tDIFF\n' \
      "$sequence" >>"${report}"
    differences=$((differences + 1))
  elif [[ -n "$before_last" && ( -z "$after_last" || "$after_last" -lt "$before_last" ) ]]; then
    printf 'sequence-%s\tlast:%s\tlast:%s\tDIFF\n' \
      "$sequence" "$before_last" "${after_last:-null}" >>"${report}"
    differences=$((differences + 1))
  else
    printf 'sequence-%s\tretained\tnondecreasing\tMATCH\n' "$sequence" >>"${report}"
  fi
done <"${before}/sequences.tsv"

required_triggers=(
  'escrow_transactions:escrow_transactions_immutable'
  'payment_ledger_entries:payment_ledger_balance_guard'
  'payment_ledger_entries:payment_ledger_entries_immutable'
  'payment_webhook_events:payment_webhook_events_immutable'
  'audit_logs:trg_audit_logs_append_only'
  'member_consent_evidence:trg_member_consent_evidence_append_only'
  'contact_requests:trg_contact_privacy_evidence_immutable'
  'marketing_consent_events:trg_marketing_consent_events_append_only'
)
for expected_trigger in "${required_triggers[@]}"; do
  table="${expected_trigger%%:*}"
  trigger="${expected_trigger#*:}"
  state="$(awk -F '\t' -v table="$table" -v trigger="$trigger" \
    '$1 == table && $2 == trigger { print $3; exit }' \
    "${after}/triggers.tsv")"
  if [[ "$state" != "O" && "$state" != "A" ]]; then
    printf 'trigger-%s.%s\t-\tmissing-or-disabled\tDIFF\n' \
      "$table" "$trigger" >>"${report}"
    differences=$((differences + 1))
  else
    printf 'trigger-%s.%s\t-\tenabled\tMATCH\n' \
      "$table" "$trigger" >>"${report}"
  fi
done

while IFS= read -r migration_file; do
  version="$(basename "$migration_file" | sed -E 's/^V([0-9._]+)__.*/\1/' | tr '_' '.')"
  [[ "$version" == "1" ]] && continue
  installed="$(awk -F '\t' -v version="$version" \
    '$2 == version && $4 == "SQL" && $6 == "t" { count++ } END { print count + 0 }' \
    "${after}/flyway-history.tsv")"
  if [[ "$installed" != "1" ]]; then
    printf 'flyway-v%s\texpected-once\tinstalled:%s\tDIFF\n' \
      "$version" "$installed" >>"${report}"
    differences=$((differences + 1))
  fi
done < <(find "${SCRIPT_DIR}/../../src/main/resources/db/migration" -maxdepth 1 \
  -type f -name 'V*__*.sql' | LC_ALL=C sort -V)

cat "${report}"
sha256sum "${report}" >"${checksum}"
[[ "$differences" == "0" ]] || dbops_die "before/after evidence has ${differences} unexpected differences"

printf 'Before/after data, timezone, extensions, schema, FK, sequence, trigger, and migration invariants match.\n'
