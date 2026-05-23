package com.viralground.backend.service;

import com.viralground.backend.dto.admin.CampaignDetailResponse;
import com.viralground.backend.dto.admin.MemberDetailResponse;
import com.viralground.backend.dto.admin.UpdateApplicationStatusRequest;
import com.viralground.backend.dto.admin.UpdateCampaignAdminRequest;
import com.viralground.backend.dto.campaign.CampaignCreateRequest;
import com.viralground.backend.entity.*;
import com.viralground.backend.event.ApplicationResultEvent;
import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import com.viralground.backend.repository.*;
import com.viralground.backend.repository.ReviewRepository;
import com.viralground.backend.repository.SubmissionMetricRepository;
import com.viralground.backend.storage.FileStorage;
import org.springframework.context.ApplicationEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AdminService {

    private final MemberRepository memberRepository;
    private final CreatorProfileRepository profileRepository;
    private final CompanyProfileRepository companyProfileRepository;
    private final CampaignRepository campaignRepository;
    private final CampaignApplicationRepository applicationRepository;
    private final EscrowTransactionRepository escrowTransactionRepository;
    private final EmailService emailService;
    private final EscrowService escrowService;
    private final ApplicationSubmissionRepository submissionRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ReviewRepository reviewRepository;
    private final SubmissionMetricRepository metricRepository;
    private final FileStorage fileStorage;

    private String resolveThumbUrl(Campaign c) {
        if (c.getThumbnailFileKey() != null && !c.getThumbnailFileKey().isBlank()) {
            return fileStorage.signedDownloadUrl(c.getThumbnailFileKey());
        }
        return c.getThumbnailUrl();
    }

    // ── 회원 관리 ──────────────────────────────────

    public Map<String, Object> getMembers(String statusStr, String search) {
        MemberStatus status = (statusStr != null && !"ALL".equals(statusStr))
                ? MemberStatus.valueOf(statusStr) : null;
        List<Member> members = memberRepository.findAllByStatusAndSearch(status, search);

        long pending = members.stream().filter(m -> m.getStatus() == MemberStatus.PENDING).count();
        long approved = members.stream().filter(m -> m.getStatus() == MemberStatus.APPROVED).count();
        long rejected = members.stream().filter(m -> m.getStatus() == MemberStatus.REJECTED).count();

        long total = memberRepository.count();
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        LocalDateTime weekAgo = LocalDateTime.now().minusDays(7);
        long todayCount = memberRepository.countByCreatedAtAfter(todayStart);
        long weekCount = memberRepository.countByCreatedAtAfter(weekAgo);

        // CREATOR 회원만 CreatorProfile 조회 대상. COMPANY/ADMIN 은 프로필이 애초에 없으므로 제외.
        List<Integer> creatorIds = members.stream()
                .filter(m -> m.getRole() == Role.CREATOR)
                .map(Member::getId)
                .toList();
        Map<Integer, String> instagramIdByMemberId = creatorIds.isEmpty()
                ? Map.of()
                : profileRepository.findByMemberIdIn(creatorIds).stream()
                        .filter(p -> p.getInstagramId() != null)
                        .collect(Collectors.toMap(CreatorProfile::getMemberId,
                                CreatorProfile::getInstagramId, (a, b) -> a));

        List<Map<String, Object>> list = members.stream()
                .map(m -> {
                    Map<String, Object> row = new java.util.HashMap<>();
                    row.put("id", m.getId());
                    row.put("email", m.getEmail());
                    row.put("name", m.getName());
                    row.put("role", m.getRole().name());
                    row.put("status", m.getStatus().name());
                    row.put("emailVerified", m.getEmailVerified());
                    row.put("createdAt", m.getCreatedAt());
                    row.put("instagramId", m.getRole() == Role.CREATOR
                            ? instagramIdByMemberId.get(m.getId())
                            : null);
                    return row;
                })
                .toList();

        Map<String, Object> stats = Map.of(
                "total", total,
                "pendingCount", pending,
                "approvedCount", approved,
                "rejectedCount", rejected,
                "todayCount", todayCount,
                "weekCount", weekCount
        );
        return Map.of(
                "stats", stats,
                "members", list
        );
    }

    public MemberDetailResponse getMember(Integer id) {
        Member member = memberRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
        CreatorProfile profile = profileRepository.findByMemberId(id).orElse(null);
        long appCount = applicationRepository.countByCreatorId(id);
        return new MemberDetailResponse(member, profile, appCount);
    }

    @Transactional
    public void deleteMember(Integer id, Integer requesterId) {
        if (id.equals(requesterId)) throw new AppException(ErrorCode.SELF_DELETE_FORBIDDEN);
        memberRepository.findById(id).orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
        memberRepository.deleteById(id);
    }

    @Transactional
    public void updateMemberStatus(Integer id, MemberStatus newStatus) {
        Member member = memberRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
        member.setStatus(newStatus);
        memberRepository.save(member);

        if (newStatus == MemberStatus.APPROVED || newStatus == MemberStatus.REJECTED) {
            emailService.notifyCreatorOfStatusChange(member.getEmail(), member.getName(), newStatus.name());
        }
    }

    // ── 캠페인 관리 ──────────────────────────────────

    public List<Map<String, Object>> getCampaigns(String statusStr) {
        CampaignStatus status = (statusStr != null && !"ALL".equals(statusStr))
                ? CampaignStatus.valueOf(statusStr) : null;
        List<Campaign> campaigns = campaignRepository.findAllByStatus(status);
        if (campaigns.isEmpty()) return List.of();

        List<Integer> campaignIds = campaigns.stream().map(Campaign::getId).toList();
        Map<Integer, Long> countByCampaignId = applicationRepository.countByCampaignIdIn(campaignIds).stream()
                .collect(Collectors.toMap(
                        CampaignApplicationRepository.CampaignCountRow::getCampaignId,
                        CampaignApplicationRepository.CampaignCountRow::getCount));

        return campaigns.stream()
                .map(c -> {
                    Map<String, Object> m = new java.util.HashMap<>();
                    m.put("id", c.getId());
                    m.put("title", c.getTitle());
                    m.put("brandName", c.getBrandName());
                    m.put("rewardAmount", c.getRewardAmount());
                    m.put("maxParticipants", c.getMaxParticipants());
                    m.put("status", c.getStatus().name());
                    m.put("escrowStatus", c.getEscrowStatus().name());
                    m.put("deadline", c.getDeadline() != null ? c.getDeadline() : "");
                    m.put("createdAt", c.getCreatedAt());
                    m.put("hidden", c.isHidden());
                    m.put("hiddenAt", c.getHiddenAt());
                    m.put("applicationCount",
                            countByCampaignId.getOrDefault(c.getId(), 0L).intValue());
                    return m;
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public CampaignDetailResponse getCampaign(Integer id) {
        Campaign campaign = campaignRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.CAMPAIGN_NOT_FOUND));
        List<CampaignApplication> apps = applicationRepository.findByCampaignIdOrderByAppliedAtDesc(id);
        List<Integer> appIds = apps.stream().map(CampaignApplication::getId).toList();
        java.util.Map<Integer, java.util.List<com.viralground.backend.dto.campaign.SubmissionHistoryItem>> byAppId =
                appIds.isEmpty()
                        ? java.util.Map.of()
                        : submissionRepository
                                .findByApplicationIdInOrderByApplicationIdAscSubmittedAtAsc(appIds).stream()
                                .collect(java.util.stream.Collectors.groupingBy(
                                        com.viralground.backend.entity.ApplicationSubmission::getApplicationId,
                                        java.util.stream.Collectors.mapping(
                                                com.viralground.backend.dto.campaign.SubmissionHistoryItem::from,
                                                java.util.stream.Collectors.toList())));
        return new CampaignDetailResponse(campaign, apps, byAppId, resolveThumbUrl(campaign));
    }

    @Transactional
    public Campaign createCampaign(CampaignCreateRequest req, Integer adminId) {
        if (req.getRewardAmount() == null || req.getMaxParticipants() == null
                || req.getRewardAmount() < 0 || req.getMaxParticipants() < 1) {
            throw new AppException(ErrorCode.INVALID_CAMPAIGN_INPUT);
        }
        int totalBudget = req.getRewardAmount() * req.getMaxParticipants();

        boolean immediatelyOpen = req.getImmediatelyOpen() == null || req.getImmediatelyOpen();

        if (req.getThumbnailFileKey() != null && !req.getThumbnailFileKey().isBlank()
                && !fileStorage.exists(req.getThumbnailFileKey())) {
            throw new AppException(ErrorCode.SUBMISSION_NOT_FOUND);
        }

        Campaign.CampaignBuilder builder = Campaign.builder()
                .title(req.getTitle())
                .description(req.getDescription())
                .brandName(req.getBrandName())
                .rewardAmount(req.getRewardAmount())
                .maxParticipants(req.getMaxParticipants())
                .totalBudget(totalBudget)
                .thumbnailFileKey(req.getThumbnailFileKey())
                .requirements(req.getRequirements())
                .deadline(req.getDeadline())
                .createdById(adminId);

        if (immediatelyOpen) {
            builder.status(CampaignStatus.OPEN)
                    .escrowStatus(EscrowStatus.FUNDED)
                    .fundedAt(LocalDateTime.now());
        } else {
            builder.status(CampaignStatus.DRAFT)
                    .escrowStatus(EscrowStatus.PENDING_DEPOSIT);
        }

        Campaign saved = campaignRepository.save(builder.build());

        if (immediatelyOpen) {
            escrowTransactionRepository.save(EscrowTransaction.builder()
                    .campaignId(saved.getId())
                    .type(EscrowTxType.DEPOSIT)
                    .amount(saved.getTotalBudget())
                    .memo("admin immediate open")
                    .build());
        }

        return saved;
    }

    @Transactional
    public void updateCampaign(Integer id, UpdateCampaignAdminRequest req) {
        Campaign c = campaignRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.CAMPAIGN_NOT_FOUND));
        if (req.getTitle() != null) c.setTitle(req.getTitle());
        if (req.getDescription() != null) c.setDescription(req.getDescription());
        if (req.getBrandName() != null) c.setBrandName(req.getBrandName());

        boolean budgetAffected = req.getRewardAmount() != null || req.getMaxParticipants() != null;
        if (budgetAffected) {
            // 예치가 이미 확정된 캠페인은 예산 변경 자체를 막는다. 이미 입금·지급된 금액과
            // 재계산된 totalBudget 이 어긋나면 정산 로직 전반이 깨지기 때문.
            EscrowStatus es = c.getEscrowStatus();
            if (es != EscrowStatus.NONE && es != EscrowStatus.PENDING_DEPOSIT) {
                throw new AppException(ErrorCode.INVALID_ESCROW_STATE);
            }
            if (req.getRewardAmount() != null) c.setRewardAmount(req.getRewardAmount());
            if (req.getMaxParticipants() != null) c.setMaxParticipants(req.getMaxParticipants());
            c.setTotalBudget(c.getRewardAmount() * c.getMaxParticipants());
        }

        if (req.getThumbnailFileKey() != null) {
            if (!req.getThumbnailFileKey().isBlank() && !fileStorage.exists(req.getThumbnailFileKey())) {
                throw new AppException(ErrorCode.SUBMISSION_NOT_FOUND);
            }
            c.setThumbnailFileKey(req.getThumbnailFileKey().isBlank() ? null : req.getThumbnailFileKey());
        }
        if (req.getRequirements() != null) c.setRequirements(req.getRequirements());
        if (req.getStatus() != null) c.setStatus(req.getStatus());
        campaignRepository.save(c);
    }

    @Transactional
    public void deleteCampaign(Integer id) {
        Campaign campaign = campaignRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.CAMPAIGN_NOT_FOUND));
        // 지원자나 에스크로 트랜잭션이 있으면 hard delete 거부 — 회계·감사 데이터 보존.
        // 운영자는 대신 "숨김" 처리해서 사용자 노출만 끌 수 있다.
        long apps = applicationRepository.countByCampaignId(id);
        long escrowTx = escrowTransactionRepository.countByCampaignId(id);
        if (apps > 0 || escrowTx > 0) {
            throw new AppException(ErrorCode.CAMPAIGN_HAS_DEPENDENCIES);
        }
        campaignRepository.delete(campaign);
    }

    /**
     * 캠페인 노출 토글. 숨김 처리 시 일반 크리에이터 목록·상세에서 사라지지만,
     * 자식 데이터(지원·에스크로)는 그대로 보존되어 정산·감사 흐름은 영향받지 않는다.
     */
    @Transactional
    public void setCampaignVisibility(Integer id, boolean hidden) {
        Campaign campaign = campaignRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.CAMPAIGN_NOT_FOUND));
        campaign.setHiddenAt(hidden ? LocalDateTime.now() : null);
        campaignRepository.save(campaign);
    }

    /** 관리자 대시보드 KPI. 플랫폼 전체 거래·완료율·누적 성과를 집계. */
    @Transactional(readOnly = true)
    public Map<String, Object> getKpi() {
        Map<EscrowTxType, Long> escrowByType = new java.util.EnumMap<>(EscrowTxType.class);
        for (Object[] row : escrowTransactionRepository.sumAmountByType()) {
            escrowByType.put((EscrowTxType) row[0], ((Number) row[1]).longValue());
        }
        long gmv = escrowByType.getOrDefault(EscrowTxType.DEPOSIT, 0L);
        long released = escrowByType.getOrDefault(EscrowTxType.RELEASE, 0L);
        long refunded = escrowByType.getOrDefault(EscrowTxType.REFUND, 0L);

        long totalCreators = memberRepository.countByRoleAndStatus(Role.CREATOR, MemberStatus.APPROVED);
        long totalCompanies = memberRepository.countByRoleAndStatus(Role.COMPANY, MemberStatus.APPROVED);
        long openCampaigns = campaignRepository.findAllByStatus(CampaignStatus.OPEN).size();

        Map<ApplicationStatus, Long> appsByStatus = new java.util.EnumMap<>(ApplicationStatus.class);
        for (Object[] row : applicationRepository.countByStatusGrouped()) {
            appsByStatus.put((ApplicationStatus) row[0], ((Number) row[1]).longValue());
        }
        long totalApps = appsByStatus.values().stream().mapToLong(Long::longValue).sum();
        long pending = appsByStatus.getOrDefault(ApplicationStatus.PENDING, 0L);
        long rejected = appsByStatus.getOrDefault(ApplicationStatus.REJECTED, 0L);
        long reachedReviewStage = totalApps - pending - rejected;
        long completed = appsByStatus.getOrDefault(ApplicationStatus.SETTLED, 0L);
        double matchingRate = totalApps == 0 ? 0.0 : (double) reachedReviewStage / totalApps;
        double completionRate = reachedReviewStage == 0 ? 0.0 : (double) completed / reachedReviewStage;

        Object[] sums = metricRepository.sumAll();
        long totalViews = sums.length > 0 && sums[0] instanceof Number n ? n.longValue() : 0;
        long totalLikes = sums.length > 1 && sums[1] instanceof Number n ? n.longValue() : 0;
        long totalComments = sums.length > 2 && sums[2] instanceof Number n ? n.longValue() : 0;

        long reviewCount = reviewRepository.count();
        double avgRating = reviewRepository.averageRating();

        return Map.of(
                "gmv", gmv,
                "released", released,
                "refunded", refunded,
                "activeCreators", totalCreators,
                "activeCompanies", totalCompanies,
                "openCampaigns", openCampaigns,
                "matchingRate", Math.round(matchingRate * 1000) / 10.0,
                "completionRate", Math.round(completionRate * 1000) / 10.0,
                "contentMetrics", Map.of(
                        "views", totalViews,
                        "likes", totalLikes,
                        "comments", totalComments),
                "reviews", Map.of(
                        "count", reviewCount,
                        "averageRating", Math.round(avgRating * 10) / 10.0));
    }

    public List<Map<String, Object>> getPendingEscrowCampaigns() {
        // PENDING_DEPOSIT 은 기업이 아직 계좌이체 완료를 누르지 않은 상태,
        // DEPOSIT_CONFIRMING 은 입금 신청 후 관리자 확인 대기. 두 상태 모두 관리자가
        // 현황을 파악해야 하므로 함께 반환하고, 프론트는 escrowStatus 로 액션 버튼을 분기한다.
        List<Campaign> campaigns = campaignRepository
                .findByEscrowStatusInOrderByDepositRequestedAtAscCreatedAtAsc(
                        List.of(EscrowStatus.PENDING_DEPOSIT, EscrowStatus.DEPOSIT_CONFIRMING));
        if (campaigns.isEmpty()) return List.of();

        List<Integer> companyMemberIds = campaigns.stream()
                .map(Campaign::getCreatedById)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        Map<Integer, String> companyNameByMemberId = companyMemberIds.isEmpty()
                ? Map.of()
                : companyProfileRepository.findByMemberIdIn(companyMemberIds).stream()
                        .collect(Collectors.toMap(CompanyProfile::getMemberId,
                                CompanyProfile::getCompanyName, (a, b) -> a));

        return campaigns.stream()
                .map(c -> {
                    String companyName = companyNameByMemberId
                            .getOrDefault(c.getCreatedById(), "-");
                    Map<String, Object> m = new java.util.HashMap<>();
                    m.put("id", c.getId());
                    m.put("title", c.getTitle());
                    m.put("brandName", c.getBrandName());
                    m.put("companyName", companyName);
                    m.put("totalBudget", c.getTotalBudget());
                    m.put("rewardAmount", c.getRewardAmount());
                    m.put("maxParticipants", c.getMaxParticipants());
                    m.put("escrowStatus", c.getEscrowStatus().name());
                    m.put("depositRequestedAt", c.getDepositRequestedAt());
                    m.put("createdAt", c.getCreatedAt());
                    return m;
                })
                .toList();
    }

    // ── 지원 관리 ──────────────────────────────────

    @Transactional
    public void updateApplication(Integer id, UpdateApplicationStatusRequest req) {
        CampaignApplication app = applicationRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.APPLICATION_NOT_FOUND));

        ApplicationStatus newStatus = req.getStatus();

        if (req.getRewardPaidAmount() != null) {
            app.setRewardPaidAmount(req.getRewardPaidAmount());
        }
        if (newStatus == ApplicationStatus.SETTLED) {
            // 이미 SETTLED 된 지원건은 재진입 금지. 캠페인 escrow 잔액이 남아있으면
            // FUNDED/PARTIALLY_RELEASED 가드는 통과하므로 여기서 별도 차단이 필요하다.
            if (app.getStatus() == ApplicationStatus.SETTLED) {
                throw new AppException(ErrorCode.INVALID_CAMPAIGN_INPUT);
            }
            // 예치금이 지급 가능한 상태인지 먼저 검증. 지급 실패 시 상태 전이가 일어나지 않도록
            // release() 호출 후에만 SETTLED / settledAt 을 확정한다.
            Campaign campaign = campaignRepository.findById(app.getCampaignId())
                    .orElseThrow(() -> new AppException(ErrorCode.CAMPAIGN_NOT_FOUND));
            if (campaign.getEscrowStatus() != EscrowStatus.FUNDED
                    && campaign.getEscrowStatus() != EscrowStatus.PARTIALLY_RELEASED) {
                throw new AppException(ErrorCode.INVALID_ESCROW_STATE);
            }
            Integer payout = app.getRewardPaidAmount() != null
                    ? app.getRewardPaidAmount()
                    : campaign.getRewardAmount();
            escrowService.release(campaign.getId(), app.getId(), payout);
            if (app.getRewardPaidAmount() == null) {
                app.setRewardPaidAmount(payout);
            }
            app.setSettledAt(LocalDateTime.now());
            markLatestSubmission(app.getId(), null, SubmissionReviewStatus.APPROVED, null);
        }
        if (newStatus == ApplicationStatus.CHANGES_REQUESTED) {
            String comment = req.getReviewComment();
            if (comment == null || comment.isBlank()) {
                throw new AppException(ErrorCode.INVALID_CAMPAIGN_INPUT);
            }
            if (app.getStatus() != ApplicationStatus.SUBMITTED) {
                throw new AppException(ErrorCode.INVALID_CAMPAIGN_INPUT);
            }
            app.setReviewComment(comment);
            markLatestSubmission(app.getId(), null, SubmissionReviewStatus.CHANGES_REQUESTED, comment);
        }
        if (newStatus == ApplicationStatus.REJECTED && app.getStatus() == ApplicationStatus.SUBMITTED) {
            app.setReviewComment(req.getReviewComment());
            markLatestSubmission(app.getId(), null, SubmissionReviewStatus.REJECTED, req.getReviewComment());
        }
        app.setStatus(newStatus);
        app.setReviewedAt(LocalDateTime.now());
        applicationRepository.save(app);

        if (newStatus == ApplicationStatus.APPROVED || newStatus == ApplicationStatus.REJECTED) {
            Member creator = memberRepository.findById(app.getCreatorId()).orElseThrow();
            Campaign campaign = campaignRepository.findById(app.getCampaignId()).orElseThrow();
            emailService.notifyCreatorOfApplicationResult(
                    creator.getEmail(), creator.getName(),
                    campaign.getTitle(), newStatus.name(), app.getRewardPaidAmount(),
                    app.getReviewComment());
        }
        if (newStatus == ApplicationStatus.CHANGES_REQUESTED) {
            Member creator = memberRepository.findById(app.getCreatorId()).orElseThrow();
            Campaign campaign = campaignRepository.findById(app.getCampaignId()).orElseThrow();
            eventPublisher.publishEvent(new ApplicationResultEvent(
                    creator.getEmail(), creator.getName(),
                    campaign.getTitle(), "CHANGES_REQUESTED", null, app.getReviewComment()));
        }
    }

    private void markLatestSubmission(Integer applicationId, Integer reviewerId,
                                      SubmissionReviewStatus status, String comment) {
        submissionRepository.findTopByApplicationIdOrderBySubmittedAtDesc(applicationId)
                .ifPresent(sub -> {
                    sub.setStatus(status);
                    sub.setReviewerId(reviewerId);
                    sub.setReviewComment(comment);
                    sub.setReviewedAt(LocalDateTime.now());
                    submissionRepository.save(sub);
                });
    }
}
