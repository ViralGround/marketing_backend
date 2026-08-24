# Sanitized staging validation

These suites qualify a release candidate; they never promote or deploy it. Their only
network targets are `https://staging.viralground.kr` and
`https://api.staging.viralground.kr`. Do not point them at production, an exact clone,
or an arbitrary preview deployment.

## Account pool and environment prerequisites

Populate the normal backend runtime from
[`.env.preproduction.example`](../.env.preproduction.example) and the frontend
build from its repository's `.env.preproduction.example`. The two templates are
one deployment contract: copy the same approved five legal document version IDs
to `LEGAL_*_VERSION` and `NEXT_PUBLIC_LEGAL_*_VERSION` respectively. Never
advance those values on only one deployment.

The protected frontend build requires exact `APP_ENV=preproduction` and
`VERCEL_ENV=production` in the isolated Vercel staging project. Bind its browser/server
Sentry releases to the full frontend commit SHA. The backend separately requires
`SENTRY_ENV=preproduction`, `SENTRY_RELEASE` exactly equal to the full lowercase backend
`GIT_COMMIT_SHA`, and a DSN whose canonical host/project path exactly matches
`SENTRY_APPROVED_HOST` and `SENTRY_APPROVED_PROJECT_ID`. The frontend and backend
project identities must be distinct. Approved identity values never include the DSN
public key, and validation errors never print a DSN or key.

Create every account through the public API only after the sanitized clone has been
masked, migrated, and guarded. Do not seed accounts with SQL and do not copy a password,
hash, session, token, email address, or OAuth identity from production.

Provision them in a separately approved boot with
`STAGING_ACCOUNT_PROVISIONING_ENABLED=true` and
`STAGING_E2E_MUTATION_ENABLED=false`. Stop the app immediately after public verification
and the approved one-shot ADMIN bootstrap. The exact lowercase synthetic email CSV is
in the protected `STAGING_PROVISIONING_ALLOWED_EMAILS` secret only, never an input or
artifact. Disable/rotate the bootstrap inputs, blank that allowlist, and set both mutation
flags false before capturing the immutable before-E2E fingerprint.

Keep six approved, email-verified synthetic accounts in the evidence allowlist:

- one COMPANY and one CREATOR for the mutating managed-beta lifecycle;
- one ADMIN for read/detail/audit checks and synthetic-campaign visibility changes;
- one disposable approved account for the concurrent refresh replay test;
- one COMPANY and one CREATOR reserved for the five-minute read-only synthetic.

All six member IDs, plus every disposable member used after the baseline, must be included
in `SYNTHETIC_MEMBER_IDS` for the before and after database evidence because login,
refresh, campaign, and audit rows are created inside the evidence window. The allowlist
cannot be extended after the before phase. Store account emails and passwords only in the
the `staging-sanitized-rc` GitHub environment secrets documented in the workflow; never put
them in workflow inputs, logs, reports, release manifests, or screenshots.

The role/business E2E must run with `EMAIL_DELIVERY_MODE=disabled`, scheduling disabled,
and the payment, Instagram, and upload feature flags false. Resend testing is a separate,
sealed run with `STAGING_EMAIL_VALIDATION_ENABLED=true` and the other two mutation modes
false. Set one exact lowercase internal address in
`STAGING_EMAIL_VALIDATION_RECIPIENT` and include that same address in
`EMAIL_ALLOWED_RECIPIENTS`; enable only global scheduling and outbox dispatch. The normal
business/authentication mutation APIs remain closed. Restore disabled mode and blank the
recipient before role E2E or database evidence. This separation preserves the final
`notification_outbox=0` and recoverable-PII=0 gate.

Before enabling the role-E2E mutation workflow:

1. Export the sanitized-clone guard variables from
   [preproduction-database-runbook.md](preproduction-database-runbook.md), including the
   release-bound sentinel ID, protected `SOURCE_SNAPSHOT_ID`, and explicit host/database
   allowlists.
2. Calculate the lowercase SHA-256 of the sealed sanitized migration root's
   `EVIDENCE-SEAL`, export it as `CLONE_EVIDENCE_SEAL_SHA256`, and run
   `scripts/db/assert-sanitized-e2e-target.sh`. It must report that the sentinel is
   live, the migration baseline is complete for this exact `RELEASE_ID`, and the live
   sentinel is bound to that exact immutable evidence seal.
3. Set `EVIDENCE_DIR` to a new `sanitized/e2e-before` root and run
   `scripts/db/sanitized-e2e-evidence.sh` with `E2E_EVIDENCE_PHASE=before`, the immutable
   synthetic allowlist, the explicit one-shot confirmation, and a dedicated sentinel-only
   attestor credential. The script immediately seals this root and atomically records its
   lowercase `CLONE_E2E_BEFORE_EVIDENCE_SEAL_SHA256` in the release-bound sentinel. It
   first proves, with counts only, that allowlisted CREATOR/COMPANY/contact rows have
   post-sentinel public-API audit and required relationship evidence, and that the sole
   ADMIN is the post-sentinel one-shot bootstrap account. Arbitrary restored-snapshot IDs
   therefore cannot exempt source rows from the before/after fingerprint. The attestor
   gate also rejects every table- or column-level application privilege, every sequence
   privilege, and
   any executable non-system `SECURITY DEFINER` path.
4. Before approving the workflow environment, the database verifier must download the
   sealed before artifact and run
   `scripts/db/assert-sanitized-e2e-before-attestation.sh` with
   `E2E_BEFORE_EVIDENCE_DIR` and `CLONE_E2E_BEFORE_EVIDENCE_SEAL_SHA256`, then store the
   approved release ID, both SHAs, schema, and immutable manifest hash in protected
   `staging-sanitized-rc` environment variables. Store the two raw seal hashes only as
   environment secrets named `APPROVED_MIGRATION_SEAL_SHA256` and
   `APPROVED_E2E_BEFORE_SEAL_SHA256`. Base64-encode the exact immutable manifest bytes
   without rewriting them and store them as environment secret
   `APPROVED_RELEASE_MANIFEST_YAML_B64`; its decoded SHA-256 must equal the protected
   manifest hash. Never use dispatch inputs or repository variables for raw seals.
   Restart the
   staging backend with that same runtime env value,
   `STAGING_ACCOUNT_PROVISIONING_ENABLED=false`, and
   `STAGING_E2E_MUTATION_ENABLED=true`. Require its non-secret safety contract to report
   `e2eBeforeEvidenceSealMatched=true` before enabling mutation. These flags are mutually
   exclusive; both are false outside their explicitly approved windows.
5. The final `after` phase uses a different, previously nonexistent `sanitized/e2e-after`
   root. It verifies the sealed before root/live sentinel, compares against the before
   fingerprint, writes the before seal+manifest parent chain, and seals the after root.
   Record both root seal hashes, the count-only synthetic-provenance report hash, and the
   workflow run URL in the release manifest.

## Manual release-candidate run

Use the frontend repository's `Sanitized staging RC validation` manual workflow. The
operator must enter the two exact confirmation phrases, the lowercase SHA-256 of the
approved release ID, both commit SHAs, the expected Flyway schema version, and the
immutable release-manifest SHA-256. The operator never enters either raw evidence seal.
The protected GitHub environment must require a reviewer. The workflow compares every
non-secret input with its protected approved variable, injects the two approved seal
secrets only into the steps that validate the runtime contract, and requires both backend
non-secret fingerprints to match. Before installing a browser or mutating, it decodes the
protected manifest, verifies its exact SHA-256, release/source/schema/sentinel/seal fields,
disabled feature gates, zero unresolved P0, and complete explicit acceptance for every
unresolved P1. This binds the approved manifest bytes to the live DB
sentinel without exposing a raw seal in the dispatch record. The workflow refuses to mutate until
`/version`, `/actuator/info`, readiness, exact staging CORS, and evil-origin rejection
match that contract.

Before the serialized role lifecycle, a browser drives the actually deployed
creator signup form with intercepted synthetic email-verification responses,
captures the form's real signup JSON, and replays it once with a deliberately
invalid verification token. The backend validates legal versions before that
token, so only `INVALID_VERIFIED_TOKEN` proves the frontend's five embedded
document versions exactly match the backend configuration. A 409
`LEGAL_DOCUMENT_VERSION_MISMATCH`, validation error, successful signup, or any
other response fails the run. The captured payload is never logged or uploaded;
the dedicated passing JUnit file is recorded as
`stagingEvidence.legalVersionContractResultSha256` in the release manifest.

The serialized API suite covers missing-CSRF rejection, role crossing, payment,
Instagram and upload fail-closed behavior, company create/publish, creator apply and
external-URL submission, company profile/campaign edit, creator profile public-consent
grant/withdrawal, change request/resubmission, nonfinancial content approval, metrics,
reviews, logout, concurrent refresh replay/family revocation, and ADMIN
KPI/member/campaign/audit reads. The ADMIN path also toggles visibility only on the
campaign created by that workflow, restores it, and proves admin payment settlement and
Instagram analytics stay fail-closed. Every run uses a unique marker. It intentionally
performs no SQL seed or cleanup and writes only through the public API as the allowlisted
synthetic accounts.

Run the workflow three consecutive times against the identical release contract for the
RC gate. The same allowlisted accounts may be reused because each workflow writes a new
uniquely marked campaign and the refresh account can log in again. Do not run SQL cleanup
or `sanitize-clone.sh` between those runs. A failed run breaks the consecutive-success
count; retain its workflow URL and safe failure metadata in the evidence record.

The following job runs read-only k6 traffic at a constant 20 requests/second with 50
preallocated/max VUs. It fails if p95 is not below one second, the HTTP failure rate is
not below 0.5%, a check rate is at or below 99.5%, or an iteration is dropped. Only the
home, public landing reads, and readiness are loaded; no authenticated or mutating route
is called. The k6 image is pinned to a reviewed immutable SHA-256 digest; changing its
version or digest requires a new review and a fresh three-run qualification window.

## Five-minute synthetic

The scheduled workflow remains inert until repository variable
`STAGING_SYNTHETIC_ENABLED=true` is set and all expected release variables plus the two
dedicated monitoring-account secret pairs exist. This includes
environment secret `STAGING_EXPECTED_EVIDENCE_SEAL_SHA256`, which the workflow maps to
`CLONE_EVIDENCE_SEAL_SHA256`, and `STAGING_EXPECTED_E2E_BEFORE_SEAL_SHA256`, mapped to
`CLONE_E2E_BEFORE_EVIDENCE_SEAL_SHA256`. The same protected environment must supply
`SOURCE_SNAPSHOT_ID` matching both seals and the live sentinel. Keep those inputs and the two monitoring
secret pairs in a separate `staging-synthetic` environment restricted to the default
branch; do not apply the manual RC approval rule to the five-minute monitor. Each run
verifies release identity and readiness, then uses real headless Chrome for home → CSRF →
login → role-specific DOM/API → logout for COMPANY and CREATOR. It validates actual
cross-subdomain Secure/HttpOnly/SameSite cookie behavior and cookie clearing. Workflow
failure is the alert signal. Output is limited to release metadata, roles, and safe journey
identifiers; credentials, cookies, CSRF values and response bodies are never printed or uploaded.

Update the expected release variables atomically whenever the approved candidate changes.
A mismatch deliberately fails before login, preventing an old or unexpected deployment
from being counted as healthy.

## External/manual evidence checklist

The automated workflow is deliberately not a substitute for flows that need a real inbox,
third-party approval, provider console, or human accessibility judgment. Record every
item below against the same release ID and SHAs. Evidence must contain only timestamps,
safe request/event IDs, redacted or hashed provider message IDs, pass/fail results, and
public-page screenshots. Never capture an email address, password, cookie, CSRF value,
OAuth state/code/token, presigned URL, or email body.

- **Signup, email verification, password reset, and withdrawal:** create two additional
  disposable allowlisted inbox/account pairs (one COMPANY and one CREATOR) through the
  public APIs. In a separate Resend window, verify signup and verification, reset each
  password and prove the old password/session fails, then withdraw both accounts and prove
  access/refresh fails immediately. Do not reuse any of the six automated accounts. Run
  this destructive account lifecycle before capturing the main before-E2E fingerprint,
  or use a separate sanitized-clone evidence directory. If either member will exist or be
  touched after the main baseline, its ID must already be in the immutable member
  allowlist; never append it after the before phase.
- **Resend delivery behavior:** after the sealed email-validation window is live, an
  authenticated ADMIN obtains a CSRF token and POSTs only
  `{ "template": "<fixed-enum>" }` to `/admin/email-validation/probes`. Queue each of
  `EMAIL_VERIFICATION_CODE`, `PASSWORD_RESET_CODE`, `CREATOR_SIGNUP_ADMIN`,
  `MEMBER_STATUS_RESULT`, `CAMPAIGN_APPLICATION_ADMIN`, `CONTACT_RECEIVED_ADMIN`,
  `APPLICATION_RESULT`, and `APPLICATION_CHANGES_REQUESTED`. The request cannot supply a
  recipient, body, identifier, or token; the response contains only `status=QUEUED`, and
  all other mutations remain closed. Capture redacted/hashed provider Message IDs and
  outbox states. Use an approved Resend project control or isolated egress fault-injection
  to produce 4xx, 5xx, and timeout failures; the same dispatcher path must show scheduled
  retry and terminal redacted DLQ plus its Sentry alert. Exercise bounce and suppression
  against the single internal inbox and capture only provider-console status evidence. If
  an approved provider/fault control is unavailable, this gate remains incomplete; never
  add an external recipient or relax the allowlist. Return to disabled mode, blank the
  fixed recipient, and drain/sanitize before database evidence.
- **Meta Instagram:** use a company-owned Instagram Professional account attached to a
  Meta Developer App test role. Manually prove approve/cancel, expired and reused state,
  account mismatch, refresh/revoke/disconnect, and signed/duplicate webhook behavior.
  Until Advanced Access and a non-owned Professional-account E2E pass, keep the public
  flag false. Evidence must omit callback query strings and tokens.
- **Sentry privacy and correlation:** trigger one controlled frontend and one backend
  error. Confirm the same safe request ID correlates them, the alert arrives, and event
  payloads contain no email/name, cookies, authorization/CSRF headers, OAuth query values,
  or presigned URLs. Store only Sentry event IDs and the scrub-check result.
- **Logout failure and rate limiting:** use a disposable account and an approved staging
  fault-injection window to make the logout backend fail, then prove the UI keeps the
  session state and shows an explicit retryable error. Separately verify login, signup,
  verification, reset, and refresh return the configured 429 contract without locking any
  of the six reusable accounts. Run this before the main baseline or pre-allowlist the
  disposable member ID; do not generate abusive traffic from the normal RC workflow.
- **Keyboard, focus, language, and visual contrast:** automated axe blocks serious and
  critical WCAG findings on public desktop/mobile routes, but a human must traverse every
  release-critical route with keyboard only in Korean and English, verify focus order and
  visible focus, modal focus trapping/return, zoom/reflow, error and empty states, and
  contrast for normal/hover/focus/disabled states. Also inspect canonical URLs, unique
  titles, noindex headers, and 404/500 recovery pages. Record route/browser/viewport and
  pass/fail; use only public or sanitized synthetic screens.
- **Provider-backed upload:** the workflow proves uploads fail closed. Presign/PUT/HEAD/
  complete/GET/delete, MIME/size/ownership/expiry, and orphan cleanup require the selected
  staging S3 provider and dedicated bucket. Until that provider evidence exists, uploads
  remain outside GO and `FEATURE_UPLOADS_ENABLED=false`.

ADMIN read/detail/audit, synthetic campaign visibility management, and payment/Instagram
fail-closed behavior are automated by the RC workflow. Destructive ADMIN member status or
deletion is intentionally excluded: it would make a reusable evidence account unstable.
If required for sign-off, perform it only on a seventh disposable member created through
the public API before the baseline, include that ID in the immutable before/after
allowlist, and mutate it only after all other runs that depend on that member.

## Final after-run evidence

Perform this final cleanup only after the three consecutive RC workflows and the complete
24-hour five-minute-synthetic window have passed. `sanitize-clone.sh` disables every test
credential, so running it earlier would invalidate the monitoring journey.

Stop the application before the final database check. Follow the database runbook's
post-validation order: re-run the sanitized target guard, run `sanitize-clone.sh` to
disable test credentials and remove outbox/token material, then run
`sanitized-e2e-evidence.sh` with `E2E_EVIDENCE_PHASE=after` and the exact same member and
contact allowlists. The after phase must prove non-synthetic business fingerprints are
unchanged and sensitive-data counts are zero. Do not manually delete test rows or weaken
the fingerprint query to make a failed run pass.

The 24-hour release gate starts only after the five-minute workflow is enabled with the
final candidate variables. It requires 100% successful scheduled runs and zero unhandled
staging Sentry errors for the full window. After the window, disable the scheduled
workflow before stopping the app and collecting final evidence.
