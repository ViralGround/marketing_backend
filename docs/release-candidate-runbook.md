# Release candidate qualification

Production deployment is deliberately out of scope. This runbook produces an immutable,
reviewable candidate and evidence; a separate approval is required for any future
staging or production mutation.

## Toolchain and required checks

- Node 22 for the frontend repository.
- Java 21 for this repository.
- PostgreSQL 16 for fresh and legacy Testcontainers migration tests.
- Docker Buildx for the non-root image build. Dockerfile, Compose, Testcontainers, and
  k6 inputs must retain reviewed image digests, not mutable tags alone.
- Redis 7 and MinIO only through `compose.local-preprod.yml` for synthetic local tests.
- The Gradle wrapper distribution must include the official `distributionSha256Sum`.

Backend required checks are unit tests, `bootJar`, fresh V1 migration, legacy baseline
V1→latest migration, V3/V4/V9/V11 fail-closed fixtures, container build, SPDX SBOM,
and separate packaged-JAR dependency plus runtime-image scans with zero
HIGH/CRITICAL Trivy findings. CI rejects an integration run with fewer than
twenty-two executed, non-skipped tests. This includes a protected direct-boot refusal
that proves invalid target guards fail before Flyway history is created. Protected
normal runtime is validate-only and refuses pending, failed, or checksum-invalid
migrations; only the release-bound clone runner may migrate. The MinIO contract covers real presign, PUT, HEAD,
owner-checked completion, GET, delete, MIME/size prevalidation, signed-header tampering,
stored-length mismatch, expiry, and orphan cleanup with verified object deletion.

Authentication-code and replaceable-outbox first-row creation is serialized across
replicas with a domain-separated PostgreSQL transaction advisory lock. Only a SHA-256-
derived signed 64-bit key reaches PostgreSQL; email addresses, recipients, and codes are
not lock-table rows or SQL parameters. CI must execute both primitive commit- and
rollback-release probes plus real Spring/JPA concurrency tests for verification,
password reset, and replaceable outbox creation. Those service tests must observe
the PostgreSQL advisory wait directly and prove the final one-row/one-PENDING state.

Run synthetic, non-qualifying local dependencies using
[local-preproduction-stack.md](local-preproduction-stack.md) with
`APP_ENV=development`, or use the verified Gradle `test` runtime for isolated
H2/Testcontainers fixtures. The loopback stack is never a sanitized clone and
must not produce release evidence.
Use [preproduction-database-runbook.md](preproduction-database-runbook.md) only after a
provider-side isolated clone exists.
The database approval record must bind one protected provider `SOURCE_SNAPSHOT_ID` to
the exact clone, sanitized clone, and every clone evidence seal. Unknown public
tables/columns, production public-table RLS/policies, or an audit role with TEMP,
dangerous predefined-role membership, or executable non-system `SECURITY DEFINER`
access are unconditional release blockers.
Run the release-bound role E2E, k6 baseline, and synthetic sequence exactly as described
in [staging-validation.md](staging-validation.md).

## Runtime release contract

Use [`.env.preproduction.example`](../.env.preproduction.example) as the
canonical normal sanitized-staging variable contract. It deliberately contains
no credential, token, snapshot identifier, evidence seal, or provider secret;
blank protected values must be injected from the isolated Railway environment.
Do not adapt the production-shaped `.env.example` by changing only `APP_ENV`.
Migration and exact-compatibility wrappers continue to own their narrower
one-shot overrides.

The environment must explicitly provide:

- `RELEASE_ID`, `GIT_COMMIT_SHA`, `BUILD_TIME`
- `APP_SCHEDULING_ENABLED=false` for migration, exact-clone compatibility, and the
  initial disabled-email role E2E
- every Instagram sync/OAuth/webhook cleanup job flag `false` initially
- `NOTIFICATION_OUTBOX_ENABLED=true` and `NOTIFICATION_OUTBOX_DISPATCH_ENABLED=false`
  until allowlisted email testing begins
- `FEATURE_PAYMENTS_ENABLED=false`
- `FEATURE_INSTAGRAM_ENABLED=false` until Meta approval
- `FEATURE_UPLOADS_ENABLED=false` and `FILES_STORAGE=disabled` until an isolated
  S3-compatible bucket passes its contract suite
- preproduction `EMAIL_DELIVERY_MODE=disabled` with an empty allowlist, global
  scheduling off, and outbox dispatch off for role E2E, or `allowlist` with valid
  internal recipients for the separate Resend window; that window requires
  `STAGING_EMAIL_VALIDATION_ENABLED=true`, the other mutation modes false, one fixed
  `STAGING_EMAIL_VALIDATION_RECIPIENT` also present in the allowlist, outbox enabled,
  global scheduling on, and outbox dispatch on. Only the ADMIN+CSRF fixed-template probe
  may enqueue; normal business/auth mutations remain closed. Preproduction rejects `live`
- production `EMAIL_DELIVERY_MODE=live` requires Resend credentials, outbox enabled,
  global scheduling on, and outbox dispatch on; disabled mode cannot dispatch the outbox
- `EMAIL_DELIVERY_MODE=disabled` intentionally creates no notification-outbox row;
  code/data transaction tests that require an outbox must use an internal-recipient
  allowlist. This is a delivery-suppression mode, not a queued-for-later mode.
- `RATE_LIMIT_BACKEND=redis`, `REDIS_URL`, exact `REDIS_ALLOWED_HOSTS`,
  `RATE_LIMIT_AUTH_FAIL_CLOSED=true`; also bind `RATE_LIMIT_REDIS_ENVIRONMENT`
  exactly to `APP_ENV` and use `viralground:<APP_ENV>:rate-limit` as
  `RATE_LIMIT_REDIS_KEY_PREFIX` so staging and production counters cannot share keys
- normal protected backend Sentry requires `SENTRY_ENV` exactly equal to `APP_ENV`,
  `SENTRY_RELEASE` exactly equal to the full lowercase backend `GIT_COMMIT_SHA`, and a
  canonical HTTPS DSN exactly matching the approved backend-only
  `SENTRY_APPROVED_HOST`/`SENTRY_APPROVED_PROJECT_ID`; guarded migration/exact utilities
  keep telemetry blank
- `CLONE_EVIDENCE_SEAL_SHA256` equal to the lowercase SHA-256 of the immutable
  sanitized-migration root's `EVIDENCE-SEAL` file
- `SOURCE_SNAPSHOT_ID` equal to the protected provider ID used by both exact and
  sanitized sentinels and by the source-snapshot hash inside that evidence seal
- role-E2E runtime: `STAGING_ACCOUNT_PROVISIONING_ENABLED=false`,
  `STAGING_E2E_MUTATION_ENABLED=true`, and
  `CLONE_E2E_BEFORE_EVIDENCE_SEAL_SHA256` equal to the separately sealed
  pre-E2E fingerprint root; the two mutation-mode flags are mutually exclusive
- all five backend `LEGAL_*_VERSION` values from the same immutable approval
  record as the frontend template's five `NEXT_PUBLIC_LEGAL_*_VERSION` values;
  the deployed signup request contract is a release-gating staging test

Protected environments reject placeholder build identity. `RELEASE_ID` must be the
approved candidate ID and `BUILD_TIME` an ISO-8601 UTC timestamp. Guarded utilities
accept the existing 7–40 hexadecimal commit metadata contract, while a normal protected
runtime requires the full 40-character lowercase backend SHA through its Sentry binding.

`/actuator/info` must report the release ID, commit, build time, schema version, and a
non-secret runtime safety contract. For the disabled-email role E2E the contract must
prove a completed release-bound `sanitized` sentinel, disabled email delivery, global
and per-job scheduling off, outbox dispatch off, and payment/Instagram/upload flags
off. It must also prove that the live sentinel, backend expected seal, and the protected
environment secret are identical: the API exposes only `sha256(raw evidence seal)` as a
non-reversible fingerprint, never the raw seal. Raw seals must never be dispatch inputs
or repository/environment variables. The same three-way binding applies to the
separate E2E-before seal, and role E2E additionally requires provisioning mode off and
E2E mutation mode on. The workflow checks this contract before
login and immediately before every mutation; the backend independently rejects mutations
if the live sentinel becomes invalid. A free-form operator attestation is not evidence. Liveness
is process-only; readiness must reject traffic when the DB, completed clone sentinel,
or Flyway state is unhealthy.

## Candidate assembly and approval

1. Qualify the same frontend and backend SHAs three consecutive times. Record each
   consecutive run separately with both commit SHAs, all run URLs, immutable suite
   result hashes, completion time, and pass/fail result; a single count is insufficient.
2. Save CI run URLs, test counts, SBOM digest, vulnerability report, image digest,
   Flyway checksums, the production read-only audit hash, clone evidence hashes, and
   approvals in the release manifest. Bind the two provider-native restore receipts,
   the byte-identical exact/sanitized pre-mask origin fingerprints, their sealed provider
   binding report, and the strict sanitized before/after comparison artifact. A receipt
   must contain provider operation identity plus ordered UTC start/completion, complete
   before its clone seal, and be no more than 14 days older than qualification.
   The backend CI preserves the exact scanned Docker archive as
   `backend-image-candidate-<sha>`. An authorized later promotion must verify the archive
   SHA-256, load and push that archive without rebuilding, then record the resulting
   registry manifest digest. A Railway/Vercel build from source is not the qualified
   backend image.
3. Confirm payment endpoints fail closed and related UI is absent. Confirm Instagram
   and upload remain hidden if their external gates are incomplete.
4. Run the role-based E2E, security, accessibility, and 50-user/20-RPS performance
   suites on sanitized staging only. Every suite/seed/cleanup wrapper must first run
   `scripts/db/assert-sanitized-e2e-target.sh` against the release-bound, completed
   sentinel and save the runtime safety-contract response hash before mutation.
   JUnit evidence must contain the declared number of actual `<testcase>` elements with
   explicit zero failure/error/skipped counts; Trivy and SPDX evidence must retain their
   typed report/document metadata and nonempty results/packages. Generic pass files are
   not qualification evidence.
5. Require 24 hours of 100% synthetic success and no unhandled Sentry errors.
6. Complete legal, restore, rollback, and owner approvals. P0 must be zero; each P1
   requires an owner, due date, and explicit acceptance.

Populate `docs/templates/release-manifest.yaml`; do not edit an old signed manifest.
Every candidate receives a new release ID and artifact set. Before staging mutation,
store the exact manifest bytes (base64 encoded) in the protected GitHub environment;
the staging workflow verifies the decoded SHA-256 and safety-critical fields rather than
trusting a free-form hash input alone. A manifest with unresolved P0 or an unowned,
undated, unapproved P1 is rejected.

The template uses schema version 5. After all evidence is closed and before any future
manual promotion approval, set the restricted copy to `status: QUALIFIED` and run the
frontend repository's independent final gate with Node 22:

```powershell
cd ..\marketing_frontend
npm run verify:release-manifest:qualified -- <absolute-path-to-restricted-release-manifest.yaml>
```

This is separate from the staging workflow's pre-mutation DRAFT binding check. It reads
the manifest only and performs no deployment or database operation. Save the command
result with the immutable manifest; any template value, null, empty required evidence,
unsafe gate, or incomplete approval fails qualification.

Evidence directories are release/sentinel-bound, newly created for one run, and never
reused. Store the final root manifest/hash after all child artifacts are closed. A
collision, existing artifact, or attempt to append after the final seal rejects the
candidate rather than overwriting evidence.

All third-party GitHub Actions and the k6 container must use reviewed immutable commit
SHAs or image digests. Dependabot may propose updates, but a release workflow must never
be enabled while a mutable action tag or unpinned test image remains.

## Repository and deployment controls before the first push

Do not push either `launch-p0` branch until the Vercel and Railway production projects
have automatic production deployment disabled and the change is independently
verified. Preview/staging deployments must target separate projects and secrets; a PR
must never update the current production deployment.

Apply the same `main` ruleset to both repositories: pull requests required, direct and
force pushes/deletion blocked, one non-author approval required, conversations resolved,
and every frontend/backend/integration CI job required and current with the head SHA.
Disable administrator bypass for the release path. Record the ruleset export or
screenshots, auto-deploy-disabled evidence, reviewer, and timestamp in the restricted
release record before opening the two PRs. Opening or merging a PR is a separate
authorized action and never follows automatically from local qualification.

## Staging ADMIN account bootstrap

There is no public ADMIN signup endpoint. CREATOR and COMPANY accounts use the public
staging APIs, while ADMIN creation is a separately approved one-shot bootstrap window.
Before that window, record the approver and bounded time. Enable the explicit bootstrap
switch and confirmation only for one isolated startup. The bootstrap process commits only
an approved, verified ADMIN and then intentionally terminates; an existing email is accepted
only when it already has that exact state, and the process still terminates. Immediately
disable the switch, remove the bootstrap credentials, rotate/revoke the bootstrap secret,
and restart the normal web process. Save non-secret creation, disabled-state, rotation, and subsequent runtime
safety-contract hashes in the manifest. Normal role E2E is forbidden while any ADMIN
bootstrap input remains enabled or populated.

## Rollback document approval

The rollback runbook is an immutable input, not a free-form version label. Record its
document version, SHA-256, approver, and approval time in the manifest before candidate
qualification. Any runbook edit requires a new hash and approval.

## V14 authentication cutover and rollback

V14 adds `members.auth_version`, and the new backend requires the same version in an
access JWT. Every JWT issued by the pre-V14 backend lacks that claim and is rejected
after cutover. Treat a one-time forced re-login for every user as an intentional
release behavior; announce it and record approval in the manifest.

The authentication smoke suite must prove that a captured pre-V14 access token is
rejected, a fresh login succeeds, refresh rotation still succeeds once, and password
reset or withdrawal immediately rejects both the prior access and refresh tokens.
Evidence must contain token fingerprints or result counts only, never raw tokens.

A blind rollback to pre-V14 code would remove the new access-token revocation check.
After any password reset, withdrawal, or `auth_version` increment occurs on the new
backend, prefer forward-fix. If old-code rollback is unavoidable, the incident owner
must approve a global session invalidation procedure (JWT signing-key rotation plus
refresh-token revocation) before the old backend receives traffic. This is a security
rollback gate, not an automatic migration step.
