# 크리에이터 인스타그램 연동 (애그리게이터 기반) — 설계

- 작성일: 2026-06-10
- 상태: 설계 승인 대기 (구현 전)
- 범위: `marketing_backend` + `marketing_frontend` (양 레포)

## 1. 목적

관리자 릴스 분석 대시보드를 **실제 인스타그램 지표**(조회수·좋아요·댓글·공유수)로 채운다.
정식 Meta 앱 심사(시간·사업자 인증 부담)를 우회하기 위해, 크리에이터가 서드파티
애그리게이터(**Phyllo**)로 본인 계정을 연결하면 그 동의를 기반으로 릴스 지표를 수집한다.
애그리게이터는 이미 Meta 심사를 통과한 앱으로 크리에이터 연결을 대행하므로, 우리는
구독료로 자체 심사를 우회하고 30명+ 규모로 확장할 수 있다.

## 2. 핵심 결정 (확정)

| 항목 | 결정 |
|---|---|
| Provider | **Phyllo** (어댑터 뒤에 두어 교체 가능) |
| 수집 방식 | **스케줄 배치**(기본 매일 00:00 KST, `instagram.sync.cron` 설정으로 12h 등 변경) **+ 관리자 수동 "지금 동기화"** |
| 연동 강제 시점 | **릴스 제출 시점** — 지원은 자유, 제출 시 연결되어 있으면 자동 추적, 미연결이면 `MANUAL`(수동 추적)로 표시. 하드 차단 아님 |
| 빌드 범위 | 키 발급 전 — 어댑터·데이터모델·연결 UI·동기화 잡·shares·폴백·테스트까지 구현, **실제 Phyllo HTTP 호출은 키 들어오면 feature flag로 활성화** |
| 비용·계정 | Phyllo 구독료(플랜은 가입 시 확인). **계정 생성·API 키 발급은 사용자가 직접** 수행 |

## 3. 아키텍처

### 3.1 Provider 어댑터 (backend)

- 기존 `InstagramMetricsProvider.fetch(String reelUrl)` 유지 — 대시보드 집계가 사용. 반환 `ReelMetrics`에 `shares` 추가.
- 신규 포트 `InstagramConnectionProvider`:
  - `String createConnectToken(int creatorId)` — 크리에이터 연결용 토큰/URL 발급
  - `ConnectedAccount resolveConnection(...)` — 연결 완료(콜백/웹훅) 처리해 providerAccountId·igUsername 확보
  - `ReelMetrics fetchReelMetrics(CreatorInstagramConnection conn, String reelUrl)`
- 구현: `PhylloInstagramProvider` + `PhylloClient`(HTTP, WebClient/RestClient). `MockInstagramMetricsProvider` 유지(데모·테스트).
- 전환 플래그: `instagram.provider=phyllo|mock` (`@ConditionalOnProperty`). 키 미설정 시 mock 기본.

### 3.2 데이터 모델 (backend)

- **신규 엔티티 `CreatorInstagramConnection`**: `id`, `creatorId`(unique, Member 참조), `provider`("PHYLLO"), `providerAccountId`, `igUsername`, `status`(PENDING/CONNECTED/ERROR/DISCONNECTED), `connectedAt`, `lastSyncedAt`, `lastError`. **민감 토큰(access token)은 Phyllo가 보관 → 우리는 연결 참조 id만 저장**(보관 최소화).
- `ReelMetrics` 레코드에 `long shares` 추가.
- **지표 스냅샷 저장 `ReelMetricSnapshot`**(신규): `applicationId`, `views`, `likes`, `comments`, `shares`, `source`(AUTO/MANUAL), `capturedAt`. 대시보드는 application별 **최신 스냅샷**을 읽어 빠르게 집계. (기존 `SubmissionMetric`는 수동 입력 폴백 소스로 유지.)
- **추이(viewsTrend) 출처**: Phyllo의 일별 시계열 필드에 의존하지 않고, **우리가 매일 쌓는 `ReelMetricSnapshot` 시계열(연속 스냅샷 간 조회수 증가분)** 으로 구성. 따라서 스냅샷이 누적되며 추이가 점진적으로 채워진다(연동 초기엔 데이터 적음). `ReelMetrics.dailyViews`는 Mock/데모 추이 표현용으로만 유지.
- 대시보드 DTO(`ReelAnalyticsResponse.ReelItem`/`Summary`/`CampaignGroup`)에 `shares` + `source`(자동/수동/미연동) 추가.

### 3.3 동기화 잡 (backend)

- `ReelMetricSyncService` + `@Scheduled`(cron 기본 `0 0 0 * * *`, KST). 인터벌은 `instagram.sync.cron` 설정.
- 동작: `status=CONNECTED` 크리에이터의 `submissionUrl` 보유 application들 → Phyllo로 지표 조회 → `ReelMetricSnapshot` upsert(`source=AUTO`) + `lastSyncedAt` 갱신. 실패는 `lastError` 기록 후 다음 주기 재시도.
- 관리자 수동: `POST /admin/reel-analytics/sync` → 즉시 동기화. 대시보드 "지금 동기화" 버튼. (대량이면 비동기 처리 + 진행 표시.)

### 3.4 연결 플로우 (frontend + backend)

- 크리에이터 마이페이지에 "인스타그램 연결" 섹션: 상태 배지(연결됨 @아이디 / 미연결 / 오류) + [연결하기]/[연결 해제].
- [연결하기] → `POST /creator/instagram/connect-token` → Phyllo connect URL/SDK 토큰 → Phyllo 연결 화면(크리에이터 인스타 로그인·동의) → 완료 콜백/웹훅 → 백엔드가 `CreatorInstagramConnection` 저장(CONNECTED).
- 상태 조회 `GET /creator/instagram/connection`, 해제 `DELETE /creator/instagram/connection`.
- 새 크리에이터 경로(`/creator/...`)는 `proxy.ts` matcher에 이미 포함됨(추가 작업 불요).

### 3.5 연동 강제 (릴스 제출 시점)

- 릴스 제출(submissionUrl 등록) 화면/API: 연결됨이면 자동 추적 대상으로 마킹(`source=AUTO` 후보), 미연결이면 "연결 시 자동 추적됩니다" 안내 + `source=MANUAL`로 진행. **제출 자체를 막지 않는다.** (개인계정만 있는 크리에이터 배제 방지.)

### 3.6 상태 가시성

- 크리에이터: 마이페이지 연결 배지.
- 관리자: 회원 목록/상세에 연동 배지, 릴스 분석 대시보드에서 릴스별 `source` 칩(자동/수동/미연동) + "연동 크리에이터 N/M명" 요약.

## 4. 데이터 흐름

1. 크리에이터 연결 → `CreatorInstagramConnection(CONNECTED)`.
2. 크리에이터가 캠페인 릴스 제출(`submissionUrl`).
3. 스케줄/수동 동기화 → 연결된 크리에이터 릴스 → Phyllo fetch → `ReelMetricSnapshot(AUTO)` 저장.
4. 대시보드 → 최신 스냅샷 집계(shares 포함) + `source` 표시.
5. 미연결/실패 → 수동 입력(`SubmissionMetric`) 폴백 또는 "미연동" 표시.

## 5. 에러·폴백

- Phyllo 호출 실패: 다음 주기 재시도, `lastError` 기록, 대시보드는 직전 스냅샷 유지(필요 시 "n일 전 동기화" 표시).
- 미연결 크리에이터: `source=MANUAL`(수동 입력 사용) 또는 "미연동".
- 키 미설정/`instagram.provider=mock`: Mock provider(데모) 사용 — 대시보드 안 깨짐.

## 6. 보안·개인정보

- 민감 토큰은 Phyllo 보관, 우리는 연결 참조 id만.
- 크리에이터 연결 = 명시 동의(연결 화면). 연결·해제 시각 기록, 해제 시 동기화 중단.
- 키는 환경변수(`PHYLLO_CLIENT_ID`, `PHYLLO_SECRET`), `.env` 커밋 금지.

## 7. 테스트 (TDD)

- `ReelMetrics.shares` 집계, 대시보드 `source` 분기.
- `ReelMetricSyncService` 단위테스트(`PhylloClient` 목): 연결된 크리에이터만 조회, upsert, 실패 시 `lastError`.
- 연결 토큰 발급/콜백 처리 테스트.
- 릴스 제출 시 자동/수동 마킹 분기.
- 기존 `ReelAnalyticsServiceTest` 확장(shares·source).

## 8. 이번 빌드 범위 / 키 활성화

- **이번에 구현**: 엔티티·스키마(ddl-auto update), provider 인터페이스 + Phyllo 어댑터(HTTP 작성, flag로 비활성), 동기화 서비스 + 스케줄러, 연결 API + 마이페이지 UI, shares 반영, 대시보드 source 표시, Mock·폴백, 테스트.
- **키 들어오면**: `instagram.provider=phyllo` + `.env` 키 설정 → 실제 동작. Phyllo의 정확한 엔드포인트·스코프·웹훅 형식은 **가입 후 공식 문서로 최종 확정**(아래 9 참고).

## 9. 가입 후 문서로 확정할 항목 (현재 미접근 영역)

- Phyllo 연결 토큰/콜백·웹훅의 정확한 요청·응답 형식.
- 릴스 인사이트 엔드포인트의 필드명. (사전 조사상 Phyllo insights는 likes·reach·comments·plays·saves·**shares** 제공 — shares 수집 가능으로 전제. 가입 후 필드명/제공 여부 최종 확인, 미제공 지표는 표시에서 제외.)
- 요금 플랜·호출 한도(동기화 주기·배치 크기 조정 근거).

## 10. Out of scope

- 인스타 외 플랫폼(틱톡·유튜브) 연동 — 추후.
- 크리에이터向 개인 분석 화면 — 추후(현재 관리자 대시보드 중심).
- 복수 provider 동시 운영.
