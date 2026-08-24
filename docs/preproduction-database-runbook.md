# Pre-production database clone runbook

Clone migration and application validation in this runbook never target the live
database. The current production application, database, domains, and deployments
remain unchanged. A provider snapshot/PITR must first be restored into isolated
databases with new hosts, names, credentials, and network policy. The sole production
connection described below is a separately confirmed, metadata/count-only audit using
a server-enforced read-only role and transaction; it never starts either backend.

## Safety contract

Two clones are required:

- `*_exact`: migration, evidence, restore, and old/new backend compatibility only.
- `*_staging`: deterministic sanitization followed by functional E2E.

Every clone DB operation refuses to start unless all of these checks pass:

1. Target host and database exactly match explicit allowlists.
2. Declared production host and database do not match the target.
3. The names contain no `prod`/`production` marker and have the required suffix.
4. TLS is enabled and the operator supplies the exact confirmation phrase.
5. A matching, unexpired sentinel exists inside the restored clone.
6. `SOURCE_SNAPSHOT_ID` exactly matches the sentinel's provider snapshot/PITR ID.

The exact and sanitized clone commands must receive the same protected
`SOURCE_SNAPSHOT_ID`. Every clone evidence seal stores its SHA-256, so a release cannot
substitute a different restored source without invalidating the sentinel and evidence.

`PGHOST` must be one DNS hostname, `PGPORT` one numeric port, and `PGDATABASE`/
`PGUSER` plain identifiers. Connection URIs/conninfo, host lists, Unix sockets, service
files, `PGHOSTADDR`, and inherited `PGOPTIONS` are rejected or cleared so libpq cannot
silently redirect the checked target or pre-apply `SET ROLE`.

Changing `APP_ENV` does not bypass this boundary. Automatic Flyway migration in an
unprotected development/test process is restricted to H2 or loopback PostgreSQL;
remote databases and clone suffixes are refused. The sole `_staging` exception is
the synthetic loopback database named `viralground_local_staging` from the local
Compose file.

Never reuse production application credentials. Inject a short-lived clone password
from the staging secret manager through `PGPASSWORD` or a temporary `.pgpass`; do not
put it in shell history, evidence, Git, or a URL.

## Production read-only audit (the only live connection)

Before restoring clones, a database owner must provision a dedicated audit login with
`SELECT` only. It must not be superuser, create roles/databases/schemas, bypass RLS,
belong to `pg_write_all_data`, hold table DML privileges, hold sequence
`USAGE`/`UPDATE`, database `TEMP`, execute a non-system `SECURITY DEFINER` routine, or
belong to a server-file/program, signal, checkpoint, maintenance, or subscription
predefined role. Its login/session role must match exactly and it must not be able to
`SET ROLE` directly or recursively to any role except PostgreSQL's built-in read-only
monitoring/data roles. Grant `SELECT` directly rather than through a custom settable
group. Do not reuse the production application, owner, migration, or provider admin
role. All production `public` tables must have RLS disabled and no policy objects; the
audit refuses filtered counts that could hide a blocker. Run the following only from an
approved workstation whose network policy allows that audit role:

```bash
export PGHOST='<actual-production-host>'
export PGPORT='5432'
export PGDATABASE='<actual-production-db-name>'
export PGUSER='<dedicated-production-readonly-role>'
export PGPASSWORD='<short-lived-secret-from-secret-manager>'
export PGSSLMODE='verify-full'
export PRODUCTION_DB_HOST='<same-actual-production-host>'
export PRODUCTION_DB_NAME='<same-actual-production-db-name>'
export PRODUCTION_READONLY_ROLE='<same-dedicated-production-readonly-role>'
export PRODUCTION_AUDIT_CONFIRMATION='I_ACKNOWLEDGE_PRODUCTION_READ_ONLY_METADATA_AUDIT_ONLY'
export RELEASE_ID='<approved-release-id>'
export EVIDENCE_DIR='build/preprod-evidence/<release-id>/production-readonly'
bash scripts/db/production-readonly-audit.sh
```

The script refuses any host/database/role mismatch, requires `verify-full`, applies a
15-second statement timeout and `default_transaction_read_only=on` to every connection,
and runs a privilege/RLS gate before any audit query. It records structural schema hashes,
extensions, exact table row counts, V3/V4/V9/V11 preflight counts, a count-only check for
the two known demo bootstrap identities, and Flyway-history presence/version/count only.
It never emits an email, name, row sample, starts a JAR, invokes Flyway or Hibernate, or
executes DDL/DML. The evidence path must not exist before the run. The result is sealed
once, bound to `RELEASE_ID`, made read-only, and refuses late append. Save the
`EVIDENCE-SEAL` SHA-256 and manifest checksum in the restricted release record, then
disconnect and revoke the short-lived login. The `production_demo_account_candidate`
count must be zero. Any blocker count or an existing Flyway history is a manual release
gate; it is never permission for an automatic production correction.

## Provider-side preparation and sentinel

1. Record one provider snapshot/PITR identifier and restore both clones from that exact
   source. Export one provider-native restore receipt per clone and normalize it to the
   exact JSON contract below. Operation IDs must be distinct; start/completion must be
   UTC, ordered, no more than 24 hours apart, and completion must precede the applicable
   clone evidence seal. Never hand-author a successful receipt.

   ```json
   {"schemaVersion":1,"provider":"railway","providerOperationId":"<provider-operation-id>","cloneKind":"exact","sourceSnapshotId":"<provider-snapshot-id>","targetHost":"<exact-host>","targetDatabase":"<database_exact>","status":"SUCCEEDED","restoreStartedAtUtc":"<UTC>","restoreCompletedAtUtc":"<UTC>","releaseId":"<release-id>"}
   ```

   The sanitized receipt has the same exact field set with `cloneKind=sanitized` and
   the `*_staging` target. Duplicate or extra JSON keys are refused. The repository
   contract is [templates/provider-restore-receipt.json](templates/provider-restore-receipt.json).
2. Restore to a new project/network. Deny public ingress and outbound email, webhook,
   object-storage, and Meta access.
3. Name the databases with `_exact` and `_staging` suffixes.
4. Create separate production-audit read-only, migration, exact-compatibility, staging
   application, and E2E evidence-attestor roles. The exact-compatibility role receives
   application `SELECT` and database `TEMP`, but no persistent relation/column DML,
   non-system schema `CREATE`, public sequence use/update, or role escalation. `TEMP`
   exists solely for per-JDBC-session auth shadows and must be revoked immediately after
   compatibility. The attestor receives only sentinel `SELECT` plus the two column-level updates
   shown below; it must have no application-table DML, schema creation, elevated
   attributes, sequence `USAGE`/`UPDATE`, executable non-system `SECURITY DEFINER`
   routine, or `SET ROLE` membership. The before-evidence command checks both table- and
   column-level grants; a column-only `INSERT`/`UPDATE` grant is still a release blocker.
5. Through the provider console as the clone owner, install this sentinel after the
   restore. Generate a unique random `sentinel_id`; exact clone expiry is at most 72
   hours and staging clone expiry is at most 30 days.

```sql
REVOKE CREATE, TEMP ON DATABASE <clone_database> FROM PUBLIC;
GRANT TEMP ON DATABASE <exact_clone_database> TO <compatibility_readonly_role>;
CREATE SCHEMA preprod_guard;
CREATE TABLE preprod_guard.clone_sentinel (
  sentinel_id text PRIMARY KEY,
  clone_kind text NOT NULL CHECK (clone_kind IN ('exact', 'sanitized')),
  source_snapshot_id text NOT NULL,
  release_id text NOT NULL,
  created_at timestamptz NOT NULL,
  expires_at timestamptz NOT NULL,
  destroyed_at timestamptz,
  baseline_started_at timestamptz,
  baseline_completed_at timestamptz,
  evidence_seal_sha256 text CHECK (evidence_seal_sha256 ~ '^[0-9a-f]{64}$'),
  e2e_before_evidence_seal_sha256 text
    CHECK (e2e_before_evidence_seal_sha256 ~ '^[0-9a-f]{64}$'),
  e2e_before_recorded_at timestamptz,
  CHECK (expires_at > created_at),
  CHECK ((baseline_completed_at IS NULL AND evidence_seal_sha256 IS NULL)
      OR (baseline_completed_at IS NOT NULL AND evidence_seal_sha256 IS NOT NULL)),
  CHECK ((e2e_before_recorded_at IS NULL AND e2e_before_evidence_seal_sha256 IS NULL)
      OR (e2e_before_recorded_at IS NOT NULL
          AND e2e_before_evidence_seal_sha256 IS NOT NULL))
);
INSERT INTO preprod_guard.clone_sentinel
  (sentinel_id, clone_kind, source_snapshot_id, release_id, created_at, expires_at)
VALUES
  ('<random-id>', '<exact-or-sanitized>', '<provider-snapshot-id>',
   '<approved-release-id>', now(), '<expiry>');
REVOKE ALL ON SCHEMA preprod_guard FROM PUBLIC;
REVOKE ALL ON preprod_guard.clone_sentinel FROM PUBLIC;
GRANT USAGE ON SCHEMA preprod_guard
  TO <migration_role>, <compatibility_readonly_role>, <staging_runtime_role>,
     <e2e_evidence_attestor_role>;
GRANT SELECT ON preprod_guard.clone_sentinel
  TO <migration_role>, <compatibility_readonly_role>, <staging_runtime_role>,
     <e2e_evidence_attestor_role>;
GRANT UPDATE (baseline_started_at, baseline_completed_at, evidence_seal_sha256)
  ON preprod_guard.clone_sentinel TO <migration_role>;
GRANT UPDATE (e2e_before_evidence_seal_sha256, e2e_before_recorded_at)
  ON preprod_guard.clone_sentinel TO <e2e_evidence_attestor_role>;
```

Never create `preprod_guard` in production. The sentinel is not included in any
Flyway migration for this reason.

## Required shell environment

Use a clean shell and explicit values. `CLONE_ALLOWED_*` may contain comma-separated
values but should normally contain exactly one target.

```bash
export PGHOST='<clone-host>'
export PGPORT='5432'
export PGDATABASE='viralground_20260822_exact'
export PGUSER='<clone-readonly-or-migration-role>'
export PGSSLMODE='verify-full'
export CLONE_KIND='exact'
export CLONE_SENTINEL_ID='<random-id>'
export SOURCE_SNAPSHOT_ID='<exact-provider-snapshot-id-used-for-both-clones>'
export CLONE_ALLOWED_HOSTS='<clone-host>'
export CLONE_ALLOWED_DATABASES='viralground_20260822_exact'
export PRODUCTION_DB_HOST='<actual-production-host-for-refusal-check-only>'
export PRODUCTION_DB_NAME='<actual-production-db-name-for-refusal-check-only>'
export DBOPS_CONFIRMATION='I_ACKNOWLEDGE_THIS_IS_A_DISPOSABLE_CLONE'
export RELEASE_ID='<approved-release-id-bound-to-the-sentinel>'
export EVIDENCE_DIR="build/preprod-evidence/<release-id>/exact/migration"
```

For clone commands, `PRODUCTION_DB_HOST` and `PRODUCTION_DB_NAME` are mandatory refusal
comparison values, not connection targets. Only the separately confirmed
`production-readonly-audit.sh` uses an exact match to authorize its read-only live
metadata audit.

`SOURCE_SNAPSHOT_ID` is not a descriptive operator note: every shell/JVM sentinel lookup
compares it, and every exact/sanitized `EVIDENCE-SEAL` contains its SHA-256. A mismatch
requires destroying the clone and restoring the approved source; never edit the sentinel.

## Exact clone sequence

The compatibility verifier defaults to `python3`. On Windows Git Bash where the
Microsoft Store `python3` launcher is only a stub, set `DBOPS_PYTHON_BIN=python`
to select the installed interpreter explicitly.

1. Prepare the immutable release jar, its SHA-256, the complete preproduction runtime
   environment, and both DB confirmation phrases. The runner requires every feature,
   scheduler, delivery, demo, and bootstrap path to be explicitly disabled.

   ```bash
   export RC_JAR='build/libs/<approved-release>.jar'
   export RC_JAR_SHA256='<approved-sha256>'
   export MIGRATION_CONFIRMATION='BASELINE_V1_ON_DISPOSABLE_EXACT_CLONE_ONCE'
   bash scripts/db/migrate-exact-clone.sh
   ```

2. The wrapper itself revalidates the sentinel/allowlists, runs blocking preflight,
   captures before evidence, and refuses any existing Flyway history. The JAR's
   guarded Flyway strategy repeats the target, suffix, production deny, sentinel, and
   data checks inside the process, then atomically marks `baseline_started_at` before
   the only baseline-enabled migration. The shell does not own this transition, so a
   direct JAR boot cannot bypass it. The wrapper then boots
   twice with baseline disabled, captures after evidence, checks invariants, compares
   row counts/financial aggregates, and seals the evidence root. The seal is bound to
   the protected source snapshot SHA-256. Only after that seal
   exists does one compare-and-set write both `baseline_completed_at` and the lowercase
   SHA-256 of `EVIDENCE-SEAL`; neither field may exist without the other.
   The wrapper passes the runner-only `app.migration-runner.enabled=true`; after a
   successful full startup the application explicitly closes its context and exits 0.
   A startup failure is nonzero, and GNU `timeout` exit 124 is always treated as a
   failed attempt—never as migration success.
   Migration-runner validation deliberately skips web-runtime HTTPS/CORS/cookie,
   Resend, Sentry, managed Redis, and final legal-document requirements. The wrapper
   overrides those integrations with blank/local synthetic values and supplies a
   non-production JWT placeholder, so no external service credential or egress is
   required. DB guards, release identity, Flyway/Hibernate validation, feature flags,
   scheduler flags, outbox, and bootstrap prohibitions remain mandatory.
   This release intentionally strengthens the not-yet-production-applied V3 audit.
   The workflow is valid only when `flyway_schema_history` is absent before the V1
   baseline; the wrapper enforces that. Recreate any disposable development database
   that previously applied an older V3—never repair/overwrite its checksum, and never
   use this path if read-only production evidence shows an existing Flyway history.
3. If any step fails after `baseline_started_at`, never rerun against that clone. Save
   the non-PII failure marker, destroy the clone, restore a fresh snapshot, install a
   new sentinel, and investigate before another approved attempt.

   Each migration wrapper captures `source-origin-fingerprint.tsv` before any sanitized
   masking write. After both clone roots exist, run
   `verify-provider-restore-binding.sh` with the two actual provider receipts and the two
   pre-mask fingerprint files. It refuses mismatched source/release/target/provider,
   duplicate operation IDs or JSON keys, implausible restore timing, and any byte
   difference between the non-sensitive origin fingerprints. Preserve its sealed root;
   the release manifest binds both receipts, both fingerprints, the binding report, and
   the binding root seal.

The before/after comparator treats V2+ additions as expected deltas while requiring
every legacy column/index/trigger and every legacy constraint except V9's intentional
`ck_members_status` replacement to remain. V11 may clear an invalid legacy homepage
(reported by preflight as a warning); row counts and financial aggregates must stay
unchanged, database timezone and the complete extension/version set must match exactly,
and all V11/V16 constraints must exist exactly once and be validated.

4. Provide the immutable archived old backend artifact and run the guarded compatibility
   wrapper. It rechecks the completed exact sentinel and both artifact SHA-256 values.
   Use one pre-approved, least-privilege, internally owned compatibility member whose
   email/password are protected secrets and whose member ID is recorded in the restricted
   approval record. The DB role must be distinct from migration, have application
   `SELECT` plus database `TEMP`, and have no persistent DML, non-system schema `CREATE`,
   public sequence use/update, executable non-system `SECURITY DEFINER` path, elevated
   membership, or `SET ROLE` path.

   ```bash
   export LEGACY_JAR='build/legacy/<archived-production>.jar'
   export LEGACY_JAR_SHA256='<approved-legacy-sha256>'
   export COMPAT_PGUSER='<dedicated-exact-compatibility-readonly-role>'
   export COMPAT_PGPASSWORD='<short-lived-readonly-secret>'
   export COMPAT_LOGIN_MEMBER_ID='<approved-internal-member-id>'
   export COMPAT_LOGIN_EMAIL='<protected-approved-login-email>'
   export COMPAT_LOGIN_EMAIL_SHA256='<sha256-of-lowercase-email>'
   export COMPAT_LOGIN_PASSWORD='<protected-approved-login-password>'
   export COMPAT_SUCCESS_LOGIN_CONFIRMATION='USE_APPROVED_COMPATIBILITY_ACCOUNT_WITH_TEMP_ONLY_AUTH_WRITES'
   export MIGRATION_EVIDENCE_DIR='build/preprod-evidence/<release-id>/exact/migration'
   export EVIDENCE_DIR='build/preprod-evidence/<release-id>/exact/compatibility'
   bash scripts/db/verify-exact-backend-compatibility.sh
   ```

   The wrapper verifies the migration evidence seal, derives its hash itself, and
   requires the live exact sentinel to contain the same value. It then starts the RC
   and archived binary sequentially on loopback only. RC
   Flyway is validate-only and legacy Flyway is disabled; both use `ddl-auto=validate`,
   scheduler/features/external adapters off, and a one-connection pool whose init SQL
   creates private `pg_temp` shadows for the approved member, refresh tokens, audit log,
   and audit sequence. Thus refresh locking and token rotation are real while all auth
   writes disappear with the JDBC session; the role cannot write a persistent object.
   It verifies `GET /landing/featured-campaigns`, a failed synthetic login, a successful
   approved login, and successful refresh for both binaries. Evidence stores only status,
   response/cookie-name-and-attribute shape hashes, and the approved email hash—never
   credentials, cookie values, response bodies, or the email itself. The wrapper fails
   unless RC and legacy match all four endpoint contracts;
   `http-contract-comparison.tsv` is the qualification artifact. The verified JDBC URL is supplied explicitly
   to both JARs, and ambient Spring/datasource/config overrides are refused. A multiset
   fingerprint of every base table in every non-system schema must be byte-identical
   before and after both startups/smokes, and the temp objects must be absent after each
   backend exits. `RC_JAR` and `RC_JAR_SHA256`
   are reused from step 1. If the archived artifact or digest is unavailable, this is
   an external release gate—not permission to synthesize an old JAR or mark compatibility
   passed. Rotate the compatibility member password and revoke the role's database
   `TEMP`, login, and secret immediately after the run; missing rotation/revocation is a
   release blocker. The wrapper first verifies the sealed migration evidence root, refuses to
   append to it, creates a fresh compatibility evidence root, and seals that root after
   the no-change fingerprint passes. No business write E2E runs on exact clone.

   The inner row/catalog/sequence multiset uses PostgreSQL MD5 only as a compact equality
   fingerprint; authorization never depends on it. The full before/after files, HTTP
   comparison, evidence manifest, and root seal are bound with SHA-256.
5. Restore the pre-migration snapshot into another new `*_exact` database, install a
   new exact sentinel, capture `EVIDENCE_PHASE=restored`, and complete the restore
   evidence template. Target RTO is four hours.
6. Mark the sentinel `destroyed_at`, revoke the clone roles, destroy both exact clone
   databases within 72 hours, and retain only non-PII evidence.

Only the guarded exact/sanitized migration wrappers invoke Flyway, and only after
their clone-specific migration confirmation plus the JVM-enforced database-side one-shot state
transition. They share the same fail-closed engine; inspection, masking, and evidence
scripts never invoke Flyway by themselves.

## Sanitized clone sequence

1. Restore the same `SOURCE_SNAPSHOT_ID` used by exact validation into a separate
   `*_staging` database and install a `sanitized` sentinel with that exact value.
2. Ensure exclusive access. Set the normal guard variables plus:

   ```bash
   export CLONE_KIND='sanitized'
   export SANITIZE_CONFIRMATION='ERASE_PII_ON_DISPOSABLE_SANITIZED_CLONE'
   ```

3. Prepare the same immutable release jar/runtime identity and keep every scheduler,
   integration, delivery, demo, and bootstrap path disabled. Set the sanitized-only
   migration confirmation and invoke the guarded wrapper:

   ```bash
   export EVIDENCE_DIR='build/preprod-evidence/<release-id>/sanitized/migration'
   export RC_JAR='build/libs/<approved-release>.jar'
   export RC_JAR_SHA256='<approved-sha256>'
   export MIGRATION_CONFIRMATION='BASELINE_V1_ON_DISPOSABLE_SANITIZED_CLONE_ONCE'
   bash scripts/db/migrate-sanitized-clone.sh
   ```

4. Before its first masking write, the wrapper inventories every application table and
   column in `public`. Unknown objects are an unconditional blocker; the legacy schema
   may only be a subset of `scripts/db/public-schema-allowlist.tsv`. The inventory,
   allowlist, and checksums are included in evidence. The wrapper then refuses an
   existing Flyway history, masks the legacy schema first,
   proves its legacy PII/token counts are zero, runs blocking preflight, and captures
   sanitized before-evidence. Only then does it atomically mark
   `baseline_started_at`, baseline V1 exactly once, and migrate V2 through the latest
   version. The in-process guard—not the shell—owns the sentinel compare-and-set, so
   the baseline-enabled process cannot be retried on the same sentinel or reached by
   an unguarded direct JAR boot.
5. The wrapper forces baseline back to false for two independent Flyway/Hibernate
   validation boots, masks the migrated schema again, proves all PII/token/file/outbox
    counts are zero, requires the migrated public schema to exactly match the latest
    allowlist, captures after-evidence, compares invariants, seals the evidence
    root, and atomically binds its `EVIDENCE-SEAL` hash to `baseline_completed_at`.
    No staging application may start before this command
    exits 0. Any failure after the started marker requires a fresh restored clone and
    new sentinel and a fresh, previously nonexistent `EVIDENCE_DIR`; never continue
    with or rerun the failed clone. Every phase/artifact refuses overwrite.
    Normal `APP_ENV=preproduction` startup must retain `CLONE_KIND=sanitized`,
    `CLONE_SENTINEL_ID`, `SOURCE_SNAPSHOT_ID`, both exact target allowlists, production deny identifiers,
    `DBOPS_CONFIRMATION`, the same `RELEASE_ID`, and `CLONE_EVIDENCE_SEAL_SHA256`
    calculated from the sealed sanitized migration evidence root. Its JDBC URL must
    include TLS and `currentSchema=public`. The JVM independently refuses exact clones,
    an evidence hash that differs from the live sentinel, and any sentinel
    that is unfinished, expired, destroyed, or bound to a different release. Use the
    staging runtime role granted sentinel `USAGE`/`SELECT`; do not use the migration role.
6. Create only CREATOR and COMPANY test accounts through the public API. Public signup
   must never create ADMIN. This provisioning boot requires
   `STAGING_ACCOUNT_PROVISIONING_ENABLED=true` and
   `STAGING_E2E_MUTATION_ENABLED=false`; only the approved account provisioning paths may
   mutate, and the migration evidence seal remains mandatory. Supply the exact lowercase
   synthetic email CSV only through the protected
   `STAGING_PROVISIONING_ALLOWED_EMAILS` secret; never store it in evidence or logs.
   Create the staging ADMIN
   through an explicitly approved one-shot bootstrap window, then immediately stop the
   app, set both mutation-window flags false, blank the provisioning email allowlist,
   remove/disable both
   bootstrap inputs, rotate the bootstrap secret, and preserve redacted evidence of
   those actions. Before normal role E2E starts, prove both admin bootstrap inputs are
   blank in the effective runtime configuration. Use `example.invalid` identities and
   allowlisted test inboxes.
   Every seed, E2E, load, synthetic, or cleanup wrapper must first run the read-only
   release-bound target assertion; it refuses an unfinished/destroyed/expired/wrong
   sentinel and never changes data:

   ```bash
   export CLONE_EVIDENCE_SEAL_SHA256="$(sha256sum \
     "build/preprod-evidence/<release-id>/sanitized/migration/EVIDENCE-SEAL" | awk '{print $1}')"
   bash scripts/db/assert-sanitized-e2e-target.sh
   ```

7. Record the approved synthetic member IDs (and any preallocated synthetic contact
   IDs) and capture the durable non-synthetic business-row fingerprint immediately
   before E2E. Use a dedicated evidence-reader login as `PGUSER` and the narrow
   sentinel-only attestor credential separately. The before root is sealed immediately;
   its seal hash and timestamp are then written once to the release-bound sentinel by a
   column-level compare-and-set:

   ```bash
   export EVIDENCE_DIR='build/preprod-evidence/<release-id>/sanitized/e2e-before'
   export SYNTHETIC_MEMBER_IDS='101,102,103'
   export SYNTHETIC_CONTACT_IDS=''
   export E2E_ATTESTATION_PGUSER='<dedicated-e2e-evidence-attestor-role>'
   export E2E_ATTESTATION_PGPASSWORD='<short-lived-attestor-secret>'
   export E2E_BEFORE_ATTESTATION_CONFIRMATION='ATTEST_IMMUTABLE_SANITIZED_E2E_BEFORE_ONCE'
   E2E_EVIDENCE_PHASE=before bash scripts/db/sanitized-e2e-evidence.sh
   ```

   The IDs are not trusted merely because they appear in the allowlist. Before sealing,
   the script emits only named checks and counts and fails unless every CREATOR/COMPANY
   has a post-sentinel `MEMBER_SIGNUP` audit, its required consent/profile graph, and no
   source-sanitization marker. It also requires exactly one post-sentinel, approved and
   verified ADMIN created by the separately approved one-shot bootstrap. Any contact ID
   must have post-sentinel privacy evidence and the matching public contact API audit.
   Missing/duplicate IDs, a copied source ID, an unsupported role, or any missing graph
   row aborts before the evidence root or sentinel can be attested. The sealed
   `sanitized-e2e-synthetic-provenance.tsv` contains counts only, never account or contact
   values.

   Copy the emitted lowercase `CLONE_E2E_BEFORE_EVIDENCE_SEAL_SHA256` into the restricted
   workflow input `sanitized_e2e_before_seal_sha256`. Before any seed, role E2E, load,
   synthetic, or cleanup
   mutation, download the sealed before artifact, set `E2E_BEFORE_EVIDENCE_DIR`, and run
   `assert-sanitized-e2e-before-attestation.sh`. It verifies the complete before-root
   manifest plus the exact live sentinel hash without changing data. Restart the staging
   backend with the same `CLONE_E2E_BEFORE_EVIDENCE_SEAL_SHA256`, provisioning disabled,
   and `STAGING_E2E_MUTATION_ENABLED=true`; readiness and the mutation safety filter must
   refuse traffic unless both live sentinel seals still match. The two mutation-window
   flags are mutually exclusive. Outside provisioning or approved E2E, keep both false so
   protected preproduction mutation endpoints fail closed.

8. Using that immutable allowlist, run the same-RC role workflow three consecutive
   times without any intervening SQL cleanup or sanitization. Then keep only the
   allowlisted monitor account active for the 24-hour synthetic window. Follow
   `staging-validation.md`; do not sanitize credentials between these runs because
   that deliberately makes every account unable to log in.
9. After the 24-hour window, disable scheduling, stop the application, rerun the
   release-bound target guard, and only then run `sanitize-clone.sh` to disable test
   credentials and clear transient secrets. Capture after evidence with the exact
   same synthetic member/contact allowlist:

   ```bash
   export E2E_BEFORE_EVIDENCE_DIR='build/preprod-evidence/<release-id>/sanitized/e2e-before'
   export CLONE_E2E_BEFORE_EVIDENCE_SEAL_SHA256='<lowercase-sha256-emitted-by-before-phase>'
   export EVIDENCE_DIR='build/preprod-evidence/<release-id>/sanitized/e2e-after'
   E2E_EVIDENCE_PHASE=after bash scripts/db/sanitized-e2e-evidence.sh
   ```

   The before and after phases independently bind the exact latest public schema inventory.
   The after phase rejects an allowlist change, compares count plus full-row hashes for
   every durable business table outside the approved synthetic relationship graph,
   then requires the PII/token/file/outbox verification counts to be zero. After the
   byte comparison succeeds it writes `sanitized-e2e-comparison.tsv`, binding the
   release, source-snapshot hash, before seal, actual before/after fingerprint hashes,
   and `result=MATCHED`. It creates a fresh after root containing that comparison and a
   parent-chain artifact with the approved before seal and manifest hashes, then seals
   the after root. Before and after roots are always distinct;
   neither may reuse or append to the sealed sanitized-migration root.

## Evidence handling

Evidence contains schema metadata, counts, aggregates, checksums, and tool output only.
It must never include row samples, emails, access tokens, URLs, file keys, query
parameters, passwords, or connection strings. Store the evidence artifact encrypted
with the release record and access logging enabled.

Every successful migration, exact-compatibility, sanitized-E2E-before, and
sanitized-E2E-after evidence root contains `EVIDENCE-MANIFEST.sha256` plus
`EVIDENCE-SEAL*` control files. The seal
records the release/stage, protected source-snapshot SHA-256 for clone stages, and hashes
every prior artifact in stable path order; scripts
refuse any later append. Run `verify-evidence-seal.sh` before copying hashes into the
release record. A changed, removed, or newly appended file invalidates the root. For a
migration evidence root, the wrapper additionally writes the lowercase SHA-256 of
`EVIDENCE-SEAL` into `clone_sentinel.evidence_seal_sha256` in the same `UPDATE` that
sets `baseline_completed_at`; staging/runtime input must match that live value exactly.
The before-E2E root is independently sealed before role-E2E mutation, then its seal hash
and recorded time are bound once to the same sentinel. The after root contains the sealed
before root's seal and manifest hashes as its immutable parent chain plus the strict
before/after comparison report; qualification checks those reported hashes against the
actual indexed fingerprint files rather than accepting a generic TSV attestation.
