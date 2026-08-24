# Local synthetic dependency stack

This stack provides PostgreSQL 16, Redis, and S3-compatible MinIO using only
synthetic credentials and loopback-bound ports. It must never be initialized
from a production dump. Despite the historical Compose filename, this is not a
protected `preproduction` runtime, a sanitized clone, or release-qualification
evidence.

The database boundary deliberately rejects loopback PostgreSQL when
`APP_ENV=preproduction`. A real sanitized clone must run remotely with
`APP_ENV=preproduction`, `sslmode=verify-full`, the exact host/database and
production-deny guards, a release-bound completed sentinel, and the immutable
evidence seals described in
[preproduction-database-runbook.md](preproduction-database-runbook.md). Do not
add a localhost exception to any protected database guard.

## Start and inspect

```bash
docker compose -f compose.local-preprod.yml config
docker compose -f compose.local-preprod.yml up -d --wait
docker compose -f compose.local-preprod.yml ps
```

Local endpoints:

- PostgreSQL: `127.0.0.1:55432`, database `viralground_local_staging`
- Redis: `redis://:local-redis-only-not-secret@127.0.0.1:56379`
- MinIO S3 API: `http://127.0.0.1:59000`
- MinIO console: `http://127.0.0.1:59001`
- Bucket: `viralground-local-staging`

A manually started local application must use `APP_ENV=development`, all feature
and scheduling flags set to `false`, `FILES_STORAGE=disabled`,
`EMAIL_DELIVERY_MODE=disabled`, and mock Instagram. `APP_ENV=test` and the
`test` Spring profile are reserved for the verified Gradle test runtime using
H2 or Testcontainers; they are not supported escape hatches for a packaged or
manually started application. Enable uploads and S3 only for the isolated MinIO
contract run. Neither mode produces release evidence.

Example values for a clean local shell (all credentials below are intentionally
synthetic and exist only in this Compose file):

```bash
export DATABASE_URL='jdbc:postgresql://127.0.0.1:55432/viralground_local_staging'
export SPRING_DATASOURCE_USERNAME='viralground_local'
export SPRING_DATASOURCE_PASSWORD='local-postgres-only-not-secret'
export REDIS_URL='redis://:local-redis-only-not-secret@127.0.0.1:56379'
export RATE_LIMIT_BACKEND='redis'
export RATE_LIMIT_AUTH_FAIL_CLOSED='true'
export FEATURE_UPLOADS_ENABLED='true'
export FILES_STORAGE='s3'
export FILES_S3_ENDPOINT='http://127.0.0.1:59000'
export FILES_S3_REGION='us-east-1'
export FILES_S3_BUCKET='viralground-local-staging'
export FILES_S3_CREDENTIALS_MODE='static'
export FILES_S3_ACCESS_KEY='local-minio-admin'
export FILES_S3_SECRET_KEY='local-minio-only-not-secret'
export FILES_S3_PATH_STYLE='true'
export APP_ENV='development'
export RELEASE_ID='vg-local-synthetic'
export APP_SCHEDULING_ENABLED='false'
export FEATURE_PAYMENTS_ENABLED='false'
export FEATURE_INSTAGRAM_ENABLED='false'
export INSTAGRAM_PROVIDER='mock'
export EMAIL_MOCK='true'
export EMAIL_DELIVERY_MODE='disabled'
export NOTIFICATION_OUTBOX_DISPATCH_ENABLED='false'
```

The block above is the upload-contract variant. For normal API/UI tests, replace
`FEATURE_UPLOADS_ENABLED=true` with `false`, set `FILES_STORAGE=disabled`, and unset
every `FILES_S3_*` variable.

Pure test fixtures must run through the Gradle `test` task. That verified runtime
may select `APP_ENV=test` and H2/Testcontainers, but it must never receive a
production snapshot, hosted clone credential, protected sentinel, or protected
evidence input.

Provide separate local-only `JWT_SECRET` and `FILES_SIGNING_SECRET` values of at
least 32 characters. Do not copy any value from Vercel, Railway, or a hosted DB.

The local sentinel expires after 30 days. Recreate this purely synthetic stack
instead of extending or editing it in place.

## Stop or reset

Stopping preserves local development data:

```bash
docker compose -f compose.local-preprod.yml down
```

Reset deletes only the three explicitly named local volumes shown by `config`:

```bash
docker compose -f compose.local-preprod.yml down --volumes
```

Before resetting, verify that the Compose project name is exactly
`viralground-local-preprod`. These volumes are never used by staging or production.
