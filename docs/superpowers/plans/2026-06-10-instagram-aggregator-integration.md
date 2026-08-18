# 크리에이터 인스타 연동 (Phyllo) 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans (inline) 로 task 단위 실행. Steps 는 `- [ ]` 체크박스.

**Goal:** 크리에이터가 Phyllo로 인스타 계정을 연결하면 릴스 지표(조회·좋아요·댓글·공유)를 동기화해 관리자 대시보드를 실데이터로 채운다. 실제 Phyllo HTTP 호출(키 의존)은 스켈레톤으로 두고 mock 폴백.

**Architecture:** 기존 `InstagramMetricsProvider` 뒤에 provider 교체(mock/phyllo). 신규 `CreatorInstagramConnection`(연결 상태), `ReelMetricSnapshot`(지표 스냅샷, source=AUTO/MANUAL). `@Scheduled` 동기화가 연결된 크리에이터 릴스를 스냅샷으로 저장, 대시보드는 스냅샷을 읽음. 프론트는 마이페이지 연결 UI + 대시보드 shares/source 표시.

**Tech Stack:** Spring Boot 3.5/Java 21, JPA/H2(test), JUnit5+Mockito+AssertJ (백엔드 TDD 필수). Next.js 16/React 19/TS (프론트).

**커밋 정책:** 프로젝트 규칙상 코드 구현 후 사용자 검토→승인→커밋. 따라서 각 task 는 TDD(red→green→refactor)로 작성하되 **커밋은 전체 검토 승인 후 일괄**. (계획의 step별 commit 은 생략, 테스트 통과로 대체.)

---

## 파일 구조

**백엔드 (marketing_backend)**
- 수정 `instagram/ReelMetrics.java` — `shares` 추가
- 수정 `instagram/MockInstagramMetricsProvider.java` — shares 생성
- 신규 `instagram/InstagramConnectionProvider.java` — 연결 포트
- 신규 `instagram/MockInstagramConnectionProvider.java` — 목 구현(기본)
- 신규 `instagram/phyllo/PhylloInstagramConnectionProvider.java` — Phyllo 스켈레톤(`instagram.provider=phyllo` 시)
- 신규 `instagram/phyllo/PhylloProperties.java` — `${PHYLLO_*}` 바인딩
- 신규 `entity/CreatorInstagramConnection.java` (+ `ConnectionStatus` enum)
- 신규 `repository/CreatorInstagramConnectionRepository.java`
- 신규 `entity/ReelMetricSnapshot.java` (+ `MetricSource` enum)
- 신규 `repository/ReelMetricSnapshotRepository.java`
- 신규 `service/InstagramConnectionService.java`
- 신규 `service/ReelMetricSyncService.java`
- 신규 `controller/CreatorInstagramController.java`
- 수정 `dto/admin/ReelAnalyticsResponse.java` — `shares`, `source`, `connectedCreators`
- 수정 `service/ReelAnalyticsService.java` — 스냅샷 우선 + source + shares + 연결수
- 수정 `controller/AdminController.java` — `POST /admin/reel-analytics/sync`
- 신규 dto: `dto/creator/InstagramConnectionResponse.java`, `dto/creator/ConnectTokenResponse.java`
- 테스트: 각 service/엔티티 대응 테스트

**프론트 (marketing_frontend)**
- 신규 `src/components/creator/InstagramConnectCard.tsx`
- 수정 크리에이터 마이페이지 `src/app/creator/mypage/page.tsx` — 연결 카드 삽입
- 수정 `src/app/admin/analytics/page.tsx` + `src/components/admin/ReelAnalyticsCharts.tsx` — shares, source 칩, 연결수
- 수정 `src/app/admin/members/[id]/page.tsx` (+ 목록) — 연동 배지

---

## 백엔드 Task

### Task 1: ReelMetrics 에 shares 추가 + 대시보드 반영

**Files:** 수정 `instagram/ReelMetrics.java`, `instagram/MockInstagramMetricsProvider.java`, `dto/admin/ReelAnalyticsResponse.java`, `service/ReelAnalyticsService.java`; 테스트 `service/ReelAnalyticsServiceTest`, `instagram/MockInstagramMetricsProviderTest`.

- [ ] **Step 1 (Red):** `ReelAnalyticsServiceTest` 의 집계 테스트에 shares 기대 추가 — 각 ReelMetrics 에 shares 부여(예 aaa=5, bbb=10, ccc=15), `res.summary().totalShares()` == 30, `topReels().get(0).shares()` == 15, `res.campaigns()` 의 group shares 합 검증. (컴파일 실패 = Red)
- [ ] **Step 2:** `ReelMetrics` 에 `long shares` 추가 → `record ReelMetrics(long views, long likes, long comments, long shares, List<Long> dailyViews)`. 컴파일 깨지는 생성자 호출 전부 갱신.
- [ ] **Step 3:** `ReelAnalyticsResponse`:
  - `Summary` 에 `long totalShares` 추가
  - `ReelItem` 에 `long shares`, `String source` 추가 (source 는 Task 6에서 채움 — 지금은 기본 `"AUTO"` 또는 빈값 자리만; 컴파일용)
  - `CampaignGroup` 에 `long shares` 추가
  - 최상위에 `int connectedCreators` 추가 (Task 6에서 채움, 지금 0)
- [ ] **Step 4:** `ReelAnalyticsService.aggregate` — shares 누적(totalShares, group shares, ReelItem.shares), demo `ReelMetrics` 에 shares 생성값. `MockInstagramMetricsProvider` shares = likes 의 5~15% 범위 시드값.
- [ ] **Step 5 (Green):** `./gradlew test --tests "*ReelAnalyticsServiceTest" --tests "*MockInstagramMetricsProviderTest"` → BUILD SUCCESSFUL.

### Task 2: CreatorInstagramConnection 엔티티 + 레포

**Files:** 신규 `entity/CreatorInstagramConnection.java`, `entity/ConnectionStatus.java`, `repository/CreatorInstagramConnectionRepository.java`; 테스트 `repository/CreatorInstagramConnectionRepositoryTest`(@DataJpaTest).

- [ ] **Step 1 (Red):** `@DataJpaTest` — `findByCreatorId` 저장·조회, `creatorId` unique 제약 검증.
- [ ] **Step 2:** 엔티티: `id`, `creatorId`(unique), `provider`(String, 기본 "PHYLLO"), `providerAccountId`, `igUsername`, `status`(enum ConnectionStatus: PENDING/CONNECTED/ERROR/DISCONNECTED), `connectedAt`, `lastSyncedAt`, `lastError`. Lombok `@Entity @Getter @Builder @NoArgsConstructor @AllArgsConstructor`. 기존 엔티티 스타일 따름.
- [ ] **Step 3:** 레포 `JpaRepository<CreatorInstagramConnection, Integer>` + `Optional<…> findByCreatorId(Integer)`, `List<…> findByStatus(ConnectionStatus)`.
- [ ] **Step 4 (Green):** `./gradlew test --tests "*CreatorInstagramConnectionRepositoryTest"`.

### Task 3: 연결 provider 포트 + Mock + Phyllo 스켈레톤 + 설정

**Files:** 신규 `instagram/InstagramConnectionProvider.java`, `instagram/MockInstagramConnectionProvider.java`, `instagram/phyllo/PhylloInstagramConnectionProvider.java`, `instagram/phyllo/PhylloProperties.java`; 테스트 `instagram/MockInstagramConnectionProviderTest`.

- [ ] **Step 1 (Red):** `MockInstagramConnectionProviderTest` — `createConnectToken(creatorId)` 가 non-blank 토큰 반환, `fetchReelMetrics(conn, url)` 가 결정적 ReelMetrics(shares 포함) 반환.
- [ ] **Step 2:** 포트 인터페이스:
  ```java
  public interface InstagramConnectionProvider {
      ConnectToken createConnectToken(int creatorId);          // 연결용 토큰/URL
      ReelMetrics fetchReelMetrics(CreatorInstagramConnection conn, String reelUrl);
      record ConnectToken(String token, String connectUrl) {}
  }
  ```
- [ ] **Step 3:** `MockInstagramConnectionProvider`(`@Component @ConditionalOnProperty(name="instagram.provider", havingValue="mock", matchIfMissing=true)`) — 토큰은 `"mock-"+creatorId`, fetch 는 기존 MockInstagramMetricsProvider 위임(or 동일 시드 로직)으로 shares 포함 반환.
- [ ] **Step 4:** `PhylloProperties`(`@ConfigurationProperties("phyllo")`) — `clientId`, `secret`, `baseUrl`, `webhookSecret` (모두 `${PHYLLO_*}` 바인딩, 기본 빈문자). `PhylloInstagramConnectionProvider`(`@ConditionalOnProperty havingValue="phyllo"`) — 메서드는 **스켈레톤**: 실제 HTTP 미구현, 호출 시 `throw new UnsupportedOperationException("Phyllo 연동 미구성: 키/엔드포인트 확정 후 구현")`. (env 의존 실호출은 범위 제외.) 클래스/주입 구조·properties 바인딩은 완성.
- [ ] **Step 5:** `application.yml` 에 `instagram.provider: ${INSTAGRAM_PROVIDER:mock}` + `phyllo: { client-id: ${PHYLLO_CLIENT_ID:}, secret: ${PHYLLO_CLIENT_SECRET:}, base-url: ${PHYLLO_BASE_URL:}, webhook-secret: ${PHYLLO_WEBHOOK_SECRET:} }`.
- [ ] **Step 6 (Green):** `./gradlew test --tests "*MockInstagramConnectionProviderTest"`.

### Task 4: ReelMetricSnapshot 엔티티 + 레포

**Files:** 신규 `entity/ReelMetricSnapshot.java`, `entity/MetricSource.java`, `repository/ReelMetricSnapshotRepository.java`; 테스트 `repository/ReelMetricSnapshotRepositoryTest`.

- [ ] **Step 1 (Red):** `@DataJpaTest` — application 별 최신 스냅샷 조회 쿼리(`findTopByApplicationIdOrderByCapturedAtDesc`) + 직전 스냅샷(추이 delta용) 조회 검증.
- [ ] **Step 2:** 엔티티: `id`, `applicationId`, `views`, `likes`, `comments`, `shares`, `source`(enum MetricSource: AUTO/MANUAL), `capturedAt`(LocalDateTime). 
- [ ] **Step 3:** 레포 + `findTopByApplicationIdOrderByCapturedAtDesc(Integer)`, `findByApplicationIdOrderByCapturedAtAsc(Integer)`(추이용), `findLatestPerApplication`(JPQL group 또는 in-memory 처리는 서비스에서).
- [ ] **Step 4 (Green):** `./gradlew test --tests "*ReelMetricSnapshotRepositoryTest"`.

### Task 5: ReelMetricSyncService + 스케줄러 + 수동 트리거

**Files:** 신규 `service/ReelMetricSyncService.java`; 수정 `controller/AdminController.java`; 테스트 `service/ReelMetricSyncServiceTest`(Mockito).

- [ ] **Step 1 (Red):** 테스트 — 연결(CONNECTED) 크리에이터의 submissionUrl 보유 application 만 `connectionProvider.fetchReelMetrics` 호출 → `ReelMetricSnapshot(source=AUTO)` 저장 + connection.lastSyncedAt 갱신. 미연결 크리에이터 릴스는 스킵. fetch 예외 시 connection.lastError 기록 + 다른 건 계속.
- [ ] **Step 2:** `syncAll()`:
  - `connectionRepo.findByStatus(CONNECTED)` → creatorId 집합
  - `applicationRepo.findBySubmissionUrlIsNotNull()` 중 해당 creator + blank 아닌 것
  - 각 릴스 `fetchReelMetrics` → snapshot 저장(AUTO), 성공 시 lastSyncedAt=now(Clock 주입), 실패 시 lastError 기록
  - `@Scheduled(cron = "${instagram.sync.cron:0 0 0 * * *}", zone="Asia/Seoul")` 메서드가 syncAll 호출
- [ ] **Step 3:** `AdminController` 에 `POST /admin/reel-analytics/sync` → `syncService.syncAll()` → 200 + 요약(`{synced, failed}`).
- [ ] **Step 4 (Green):** `./gradlew test --tests "*ReelMetricSyncServiceTest"`.

### Task 6: 대시보드를 스냅샷 기반 + source/연결수로

**Files:** 수정 `service/ReelAnalyticsService.java`; 테스트 확장 `service/ReelAnalyticsServiceTest`.

- [ ] **Step 1 (Red):** 테스트 — 실데이터 경로에서 각 application 의 지표를 (a) 최신 ReelMetricSnapshot 있으면 그 값+source, (b) 없으면 수동 SubmissionMetric 폴백(source=MANUAL), (c) 둘 다 없으면 미연동(source="NONE", 0). `ReelItem.source` 검증, `summary.connectedCreators` == CONNECTED 수. 추이는 스냅샷 시계열 delta(있으면).
- [ ] **Step 2:** `realRows()` 를 스냅샷/폴백 소스에서 ReelRow 구성하도록 변경(provider.fetch 직접호출 → 저장된 스냅샷 우선). `connectedCreators` = connectionRepo CONNECTED 수. demo 경로는 그대로.
- [ ] **Step 3 (Green):** `./gradlew test --tests "*ReelAnalyticsServiceTest"`.

### Task 7: 연결 API (크리에이터)

**Files:** 신규 `service/InstagramConnectionService.java`, `controller/CreatorInstagramController.java`, `dto/creator/InstagramConnectionResponse.java`, `dto/creator/ConnectTokenResponse.java`; 테스트 `service/InstagramConnectionServiceTest`.

- [ ] **Step 1 (Red):** 테스트 — `getConnectToken(creatorId)` 가 provider.createConnectToken 위임 + PENDING 연결 레코드 upsert. `getConnection(creatorId)` 상태 반환. `markConnected(creatorId, providerAccountId, igUsername)` → CONNECTED+connectedAt. `disconnect(creatorId)` → DISCONNECTED.
- [ ] **Step 2:** 서비스 + 컨트롤러 엔드포인트: `POST /creator/instagram/connect-token`, `GET /creator/instagram/connection`, `DELETE /creator/instagram/connection`. (연결 완료 콜백/웹훅 수신부는 mock 에선 `markConnected` 직접 호출용 테스트 엔드포인트 또는 provider 위임 — Phyllo 실제 웹훅은 키 단계.) `@AuthenticationPrincipal` 로 creatorId.
- [ ] **Step 3 (Green):** `./gradlew test --tests "*InstagramConnectionServiceTest"`.

### Task 8: 릴스 제출 시 source 마킹

**Files:** 제출 처리 서비스(기존 CampaignApplication submit 경로) 수정; 테스트.

- [ ] **Step 1 (Red):** 제출 시 연결됨이면 그 application 의 초기 스냅샷 source 의도를 AUTO 로, 미연결이면 MANUAL 로 표시(또는 제출 응답에 trackingMode 포함). 테스트로 분기 검증.
- [ ] **Step 2:** 제출 서비스에서 connectionRepo.findByCreatorId 로 CONNECTED 여부 → 응답/마킹. (실제 지표는 동기화가 채움; 여기선 trackingMode 표시.)
- [ ] **Step 3 (Green):** 해당 테스트 통과.

---

## 프론트 Task

### Task 9: 크리에이터 마이페이지 인스타 연결 카드

**Files:** 신규 `src/components/creator/InstagramConnectCard.tsx`; 수정 `src/app/creator/mypage/page.tsx`.

- [ ] **Step 1:** `InstagramConnectCard` — `GET /creator/instagram/connection` 로 상태 조회(useEffect, set-state-in-effect 회피 위해 비동기 콜백에서만 setState). 상태별 표시: 연결됨(@username, [연결 해제]) / 미연결([인스타그램 연결]) / 오류. [연결] 클릭 → `POST /creator/instagram/connect-token` → `connectUrl` 새 창/리다이렉트(mock 은 즉시 연결 처리). KO/EN `t()`. 디자인 토큰/Card 재사용.
- [ ] **Step 2:** 마이페이지에 카드 삽입. tsc/eslint 0 에러.

### Task 10: 대시보드 shares + source + 연결수

**Files:** 수정 `src/app/admin/analytics/page.tsx`, `src/components/admin/ReelAnalyticsCharts.tsx`.

- [ ] **Step 1:** 타입에 `shares`, `source`, `connectedCreators` 추가. KPI 스트립에 "총 공유" 추가, 상단에 "연동 크리에이터 N/M" 표시, "지금 동기화" 버튼(`POST /admin/reel-analytics/sync` → 성공 후 refetch). 랭킹/표/캠페인 상세에 공유 컬럼 + source 칩(자동/수동/미연동). KO/EN.
- [ ] **Step 2:** tsc/eslint/landing-compile 검증.

### Task 11: 관리자 회원 연동 배지

**Files:** 수정 `src/app/admin/members/[id]/page.tsx` (+ 목록 페이지).

- [ ] **Step 1:** 회원 상세에 인스타 연동 상태 배지(백엔드 멤버 상세 응답에 연동 상태 포함 필요 시 Task 7 응답 재사용 또는 admin 전용 조회). 최소: `GET /creator/instagram/connection` admin 조회용 경로 or member detail 확장. tsc/eslint.

---

## Self-Review (spec 대비)

- shares: Task 1,10 ✓ / 연결 모델: Task 2,7,9 ✓ / 스냅샷·추이: Task 4,6 ✓ / 동기화+스케줄+수동: Task 5,10 ✓ / provider 교체+Phyllo 스켈레톤+env: Task 3 ✓ / 제출 시점 마킹: Task 8 ✓ / 상태 가시성: Task 9,10,11 ✓ / 폴백: Task 6 ✓.
- env 의존 실호출 제외: Task 3 에서 Phyllo 메서드 스켈레톤(UnsupportedOperationException) — 충족.
- 미해결: Phyllo 실제 엔드포인트/웹훅(키 단계), Task 11 admin 연동조회 경로는 구현 시 member detail 확장으로 확정.
# 보관됨 — 구현에 사용하지 말 것

이 문서는 과거 Phyllo 실행 계획이다. ViralGround는 2026-08-13부터 애그리게이터를 사용하지 않고 Meta Instagram Graph API를 직접 연결한다. 현재 운영 계약과 설정은 [`../../instagram-meta-setup.md`](../../instagram-meta-setup.md)를 따른다.
