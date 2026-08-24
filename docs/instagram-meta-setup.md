# Meta Instagram 직접 연동 운영 설정

ViralGround는 Phyllo를 사용하지 않고 **Instagram API with Instagram Login**을 직접 사용한다.
개인 계정은 지원하지 않으며 Instagram Professional(Business 또는 Creator) 계정만 연결할 수 있다.

공식 참고 문서:

- Instagram API 개요: https://developers.facebook.com/docs/instagram-platform/
- Instagram Login 방식: https://developers.facebook.com/docs/instagram-platform/instagram-api-with-instagram-login/
- Insights: https://developers.facebook.com/docs/instagram-platform/instagram-api-with-instagram-login/insights/
- Webhooks: https://developers.facebook.com/docs/instagram-platform/webhooks/
- Meta 공식 Instagram API Postman 컬렉션: https://www.postman.com/meta/instagram/documentation/6yqw8pt/instagram-api

Meta 문서와 API 버전은 변경될 수 있다. 배포 전 고정한 Graph API 버전의 changelog와 권한명을 다시 확인한다.

## 운영자가 직접 준비할 항목

1. 회사 소유 Meta Business Portfolio를 만들고 최소 두 명의 관리자를 지정한 뒤 모든 관리자에게 2FA를 강제한다.
2. Meta for Developers에서 해당 Portfolio 소유의 Business 유형 앱을 생성하고 Instagram 제품의 “API setup with Instagram login”을 활성화한다.
3. 사업자 인증, 개인정보처리방침 URL, 서비스 약관 URL, 사용자 데이터 삭제 안내/콜백 URL을 등록한다.
4. OAuth Valid Redirect URI에 백엔드 callback을 **문자열까지 정확히** 등록한다.
   - staging: `https://api.staging.viralground.kr/instagram/meta/oauth/callback`
   - `META_INSTAGRAM_REDIRECT_URI`도 완전히 같은 값이어야 한다.
5. Webhook callback에 `https://api.staging.viralground.kr/instagram/meta/webhook`을 등록하고
   `META_INSTAGRAM_WEBHOOK_VERIFY_TOKEN`과 동일한 verify token을 입력한다.
6. 회사가 소유하고 앱 역할에 등록된 테스트용 Instagram Professional 계정을 연결한다. 앱 소유 계정만 연결하면 Standard Access로 테스트할 수 있다. 제3자 크리에이터 계정을 연결하려면 필요한 권한의
   Advanced Access와 App Review가 필요하다.
7. 최소 요청 권한을 검토한다.
   - `instagram_business_basic`: 계정과 소유 미디어 식별
   - `instagram_business_manage_insights`: 미디어 insights 조회
8. App Review 제출 영상에는 로그인, 권한 설명, 계정 일치 실패, 연결 해제/권한 철회, 수집 지표의 사용 화면을 포함하고 심사용 테스트 계정을 준비한다.
9. 개발 모드에서는 App Dashboard에 역할이 있는 테스터와 테스트용 Professional 계정만 동작한다. Advanced Access 승인 전에는 `FEATURE_INSTAGRAM_ENABLED=false`로 공개 UI와 API를 닫는다. 승인 후 실제 비소유 Professional 계정 E2E까지 통과해야 공개 기능을 활성화한다.

## 필수 환경변수

```dotenv
APP_ENV=preproduction
FEATURE_INSTAGRAM_ENABLED=false
INSTAGRAM_PROVIDER=meta
META_INSTAGRAM_APP_ID=
META_INSTAGRAM_APP_SECRET=
META_INSTAGRAM_REDIRECT_URI=https://api.staging.viralground.kr/instagram/meta/oauth/callback
META_INSTAGRAM_FRONTEND_RESULT_URL=https://staging.viralground.kr/creator/mypage
META_INSTAGRAM_TOKEN_ENCRYPTION_KEY=
META_INSTAGRAM_WEBHOOK_VERIFY_TOKEN=
META_INSTAGRAM_API_VERSION=v25.0
META_INSTAGRAM_SCOPES=instagram_business_basic,instagram_business_manage_insights
```

기능을 켜면 authorization, OAuth, Graph endpoint는 각각
`https://www.instagram.com/oauth/authorize`, `https://api.instagram.com`,
`https://graph.instagram.com`만 허용된다. `preproduction`/`staging`의 redirect와 frontend
result URL은 위 예시의 host와 path까지 정확히 일치해야 하며 query, fragment, userinfo,
별도 port를 붙일 수 없다.

`META_INSTAGRAM_TOKEN_ENCRYPTION_KEY`는 CSPRNG로 만든 32-byte 키를 Base64로 인코딩한다. 예:

```shell
openssl rand -base64 32
```

키는 JWT secret 및 Meta app secret과 분리해 secret manager에 보관한다. 현재 암호문은 `v1` 단일 키 방식이므로 키를 즉시
교체하면 기존 연결 토큰을 해독할 수 없다. 키 회전 시에는 기존 키를 유지한 keyring 마이그레이션을 먼저 구현하거나 모든 사용자의
재연결을 계획해야 한다.

`production`, `preproduction`, `staging`에서 Instagram 기능을 활성화했는데
`INSTAGRAM_PROVIDER=mock`이면 애플리케이션은 기동을 거부한다. 기능이 비활성화된
초기 staging은 mock provider로 기동할 수 있지만 authorize/connection/callback/webhook
업무 처리는 fail-closed여야 한다. Meta provider를 활성화하면 필수 비밀값이 비어 있어도
기동을 거부한다.

## 프론트엔드 계약

- `POST /creator/instagram/authorize` (JWT 필요, CREATOR 전용)
  - 응답: `{ "authorizationUrl": "...", "expiresAt": "2026-08-13T...Z" }`
  - 브라우저를 `authorizationUrl`로 이동한다.
- Meta callback: `GET /instagram/meta/oauth/callback?code=...&state=...` (공개)
  - 서버가 code를 교환하고 프론트 결과 URL로 `303 See Other`를 보낸다.
  - 성공: `?instagram=connected`
  - 사용자가 취소: `?instagram=cancelled`
  - 실패: `?instagram=error&reason=invalid_state|account_mismatch|profile_required|provider_rejected|temporary_failure`
- `GET /creator/instagram/connection` (JWT 필요, CREATOR 전용)
- `DELETE /creator/instagram/connection` (JWT 필요, CREATOR 전용)
  - Meta 권한 철회 성공 후 암호화 토큰과 계정 식별자를 로컬에서 제거한다.

callback의 `code`, `state`, access token, app secret은 로그에 기록하지 않는다. OAuth state는 원문 대신 SHA-256만 DB에 저장하고
10분 만료, 사용자당 기존 미사용 state 무효화, 1회 소비를 적용한다.

## Webhook 동작과 제한

- 검증 GET은 `hub.mode=subscribe`, verify token, challenge를 확인한다.
- POST는 원문 body와 `X-Hub-Signature-256`을 app secret 기반 HMAC-SHA256으로 비교한다.
- payload 원문은 저장하지 않는다. SHA-256 event hash, entry 수, 수신 시각만 14일 보관해 재전송을 dedupe한다.
- 현재 Reel 성과 수집의 기준은 bounded polling이다. Webhook은 검증·중복방지 수신 기반만 제공하며 이벤트별 업무 처리는 아직
  연결하지 않았다. 실제 구독 field를 늘릴 때는 필요한 이벤트만 선택하고 별도 처리 테스트와 개인정보 문서 업데이트를 먼저 한다.

## 출시 전 실계정 검증

1. 등록 Instagram 아이디와 같은 Professional 계정은 연결된다.
2. 다른 계정으로 로그인하면 연결이 거부되고 새 토큰 권한이 철회된다.
3. 만료·재사용 state가 모두 거부된다.
4. 장기 토큰 만료 7일 전 갱신되고 암호문이 교체된다.
5. 동일 webhook 재전송은 두 번째부터 duplicate 처리된다.
6. 잘못된 webhook 서명은 401이다.
7. 제출 URL이 연결 계정 소유 media가 아니면 지표 수집이 실패한다.
8. 연결 해제 후 Meta의 앱 권한 목록에서도 앱이 제거되고 DB 토큰 컬럼이 NULL이다.
9. Meta rate limit/5xx에서 최대 재시도 후 한 작업만 실패하며 전체 batch는 계속된다.
10. 운영 로그·Sentry·프록시 access log 어디에도 query의 OAuth code/state/token이 남지 않는지 확인한다.
11. 공개 역할이 없는 Professional 계정으로 승인된 권한 범위의 전체 E2E가 성공한다.
12. callback/webhook URL이 staging host 외 요청을 만들지 않고 production secret·DB·bucket을 참조하지 않는다.

마지막 항목 때문에 프록시와 APM에서도 callback query string 마스킹을 설정해야 한다. 애플리케이션 로그만으로는 인프라 access
log의 query 기록을 통제할 수 없다.
