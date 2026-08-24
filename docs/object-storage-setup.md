# S3/R2 객체 저장소 사전운영 설정

## 저장소 선택

`FILES_STORAGE=local`은 로컬 개발·테스트 흐름을 그대로 유지한다. 보호 환경에서 업로드를 활성화할 때는 `FILES_STORAGE=s3`만 허용되며, 필수 설정이 없거나 커스텀 endpoint가 HTTP면 서버가 기동하지 않는다.

현재 release candidate는 공급자와 전용 bucket이 확정될 때까지
`FEATURE_UPLOADS_ENABLED=false`, `FILES_STORAGE=disabled`를 유지한다. 아래 설정은
운영 bucket이 아니라 새 `viralground-staging` bucket에만 적용한다. 운영 bucket 공유,
운영 객체 복사, 운영 자격증명 재사용은 금지한다.

AWS S3:

```dotenv
FILES_STORAGE=s3
FILES_S3_ENDPOINT=
FILES_S3_REGION=ap-northeast-2
FILES_S3_BUCKET=viralground-staging
FILES_S3_STAGING_ALLOWED_BUCKETS=viralground-staging
FILES_S3_PRODUCTION_ALLOWED_BUCKETS=<CURRENT_PRODUCTION_BUCKET>
FILES_S3_CREDENTIALS_MODE=default-chain
FILES_S3_PATH_STYLE=false
```

Cloudflare R2:

```dotenv
FILES_STORAGE=s3
FILES_S3_ENDPOINT=https://<ACCOUNT_ID>.r2.cloudflarestorage.com
FILES_S3_REGION=auto
FILES_S3_BUCKET=viralground-staging
FILES_S3_STAGING_ALLOWED_BUCKETS=viralground-staging
FILES_S3_PRODUCTION_ALLOWED_BUCKETS=<CURRENT_PRODUCTION_BUCKET>
FILES_S3_CREDENTIALS_MODE=static
FILES_S3_ACCESS_KEY=<R2_ACCESS_KEY_ID>
FILES_S3_SECRET_KEY=<R2_SECRET_ACCESS_KEY>
FILES_S3_PATH_STYLE=true
```

`static`은 access key/secret key가 모두 필수다. 임시 AWS 자격증명을 쓸 때만 `FILES_S3_SESSION_TOKEN`을 추가한다. `default-chain`은 키를 환경변수로 저장하지 않는 IRSA, ECS task role, EC2 instance role 방식에 권장한다.

`preproduction`/`staging`/`production`은 staging과 production bucket allowlist를 모두
명시해야 한다. 두 목록은 겹칠 수 없으며 현재 환경의 bucket은 해당 exact allowlist에
있어야 한다. 상대 환경 목록은 운영 bucket 공유를 막는 denylist로도 사용된다. 사용자
지정 endpoint는 HTTPS이고 DNS 결과가 public 주소여야 한다. AWS SDK의 region 기반 기본
endpoint를 쓸 때만 `FILES_S3_ENDPOINT`를 비워 둔다.

API 자격증명에는 staging bucket/prefix에 대한 `s3:PutObject`, `s3:GetObject`,
`s3:DeleteObject`만 주는 최소 권한 정책을 사용한다. S3 `HeadObject`는
`s3:GetObject` 권한을 사용한다. bucket 목록·bucket 삭제·다른 bucket 권한은 주지
않는다. 서버 측 암호화, versioning, 미완료·테스트 객체 7일 lifecycle을 공급자
콘솔과 API 결과로 각각 확인하고 증빙 hash를 release manifest에 기록한다.

## 브라우저 CORS

브라우저가 API 대신 bucket에 직접 PUT하므로 bucket CORS에 실제 프론트 origin을 등록한다. wildcard origin은 사용하지 않는다.

```json
[
  {
    "AllowedOrigins": ["https://staging.viralground.kr"],
    "AllowedMethods": ["PUT", "GET", "HEAD"],
    "AllowedHeaders": ["Content-Type", "Content-Length", "x-amz-*"],
    "ExposeHeaders": ["ETag"],
    "MaxAgeSeconds": 3600
  }
]
```

## 프론트엔드 API 계약

1. 영상은 `POST /files/presign-upload`, 이미지는 `POST /files/presign-upload/image`로 발급한다.

```json
{
  "contentType": "video/mp4",
  "sizeBytes": 123456
}
```

응답:

```json
{
  "fileKey": "submissions/7ce9b7eb-719a-4d87-853b-cd51fb98e2b0.mp4",
  "uploadUrl": "<presigned PUT URL>",
  "downloadUrl": "<presigned GET URL>",
  "expiresAt": "2026-08-13T12:15:00Z"
}
```

2. `uploadUrl`에 PUT한다. `Content-Type`은 발급 요청과 정확히 같아야 하고, body는 발급한 `sizeBytes`와 정확히 같은 원본 파일이어야 한다. `Content-Length`는 presign에 포함되며 브라우저가 `File`/`Blob` body에서 자동 설정하므로 JavaScript에서 직접 설정하지 않는다. presigned URL은 bearer secret이므로 로그·분석·에러 수집 payload에 넣지 않는다.

3. PUT이 2xx로 완료된 즉시, 같은 인증 세션과 CSRF header로 `POST /files/complete-upload`을 호출한다.

```json
{
  "fileKey": "submissions/7ce9b7eb-719a-4d87-853b-cd51fb98e2b0.mp4"
}
```

성공 `200 OK`:

```json
{
  "fileKey": "submissions/7ce9b7eb-719a-4d87-853b-cd51fb98e2b0.mp4",
  "status": "UPLOADED",
  "uploadedAt": "2026-08-13T12:01:02Z"
}
```

완료 API는 발급받은 소유자만 호출할 수 있고, S3 `HEAD`로 DB의 key/content type/content length와 객체를 비교한 후에만 `PENDING -> UPLOADED`로 전환한다. 같은 소유자의 완료 재시도는 멱등하게 같은 응답을 준다. `UPLOAD_NOT_FOUND`(404), `UPLOAD_OBJECT_MISMATCH`(400), `FORBIDDEN`(403)이 나오면 해당 key를 폼에 저장하거나 제출하면 안 된다.

`FILES_STORAGE=local`에서는 기존 `/files/upload/{key}?sig=...&exp=...` PUT이 실제 파일 검증과 상태 전환까지 수행하므로 기존 로컬 흐름이 유지된다. S3/R2 흐름에서는 완료 API가 필수다.

## 운영 검증

공급자 확정 전에는 MinIO/Testcontainers 계약 테스트만 실행한다. 실제 staging에서는
새 bucket을 만든 후 `presign -> PUT -> HEAD -> complete -> GET -> delete` 전 과정을
다시 실행한다. 과대 파일, MIME/서명 header 불일치, 다른 사용자의 key, 만료 URL,
존재하지 않는 객체, 저장 길이 불일치, orphan cleanup의 실제 객체 삭제도 모두
fail-closed여야 한다. 공급자·bucket·IAM·CORS·암호화·versioning·lifecycle 증빙 중
하나라도 없으면 업로드는 최종 GO 대상이 아니다.

- Flyway `V5__object_storage_upload_status.sql`이 기존 `uploaded_at` 레코드를 `UPLOADED`로 이관한다.
- `FILES_SIGNING_TTL`은 1초~7일만 허용하며 기본은 15분이다.
- HEAD/삭제 호출은 SDK standard retry(최대 3회 시도)와 시도당/전체 timeout으로 유한하게 끝난다.
- 커스텀 endpoint에 query, fragment, URL user-info를 넣으면 기동이 차단된다.
- access key, secret key, session token, presigned URL은 서버 정보/접근 로그에 남지 않는다. 구조화 로그는 카테고리, content type, size, 성공/거부 이벤트만 남긴다.
- presigned PUT URL은 완료 API 호출 후에도 만료 시각까지는 유효하다. TTL을 업로드에 필요한 최소 값으로 유지하고 bucket versioning을 켜서 동일 길이·타입 객체로의 만료 전 재PUT 사고를 복구할 수 있게 한다. 강한 불변성이 필요한 규제 환경은 staging key→final key server-side copy 또는 공급자 Object Lock을 별도 설계해야 한다.

## 근거

- AWS SDK for Java 2.x endpoint/path-style 설정: https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/endpoint-config.html
- AWS SDK for Java 2.x presigned URL: https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/examples-s3-presign.html
- AWS SDK for Java 2.x HTTP/API timeout: https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/timeouts.html
- AWS SDK for Java 2.x URLConnection client: https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/http-configuration-url.html
- AWS SDK for Java 2.x retry strategy: https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/retry-strategy.html
