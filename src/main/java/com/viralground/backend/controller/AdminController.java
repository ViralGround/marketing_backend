package com.viralground.backend.controller;

import com.viralground.backend.config.AuthUser;
import com.viralground.backend.dto.admin.CampaignDetailResponse;
import com.viralground.backend.dto.admin.ContactRequestResponse;
import com.viralground.backend.dto.admin.MemberDetailResponse;
import com.viralground.backend.dto.admin.ReelAnalyticsResponse;
import com.viralground.backend.dto.admin.UpdateApplicationStatusRequest;
import com.viralground.backend.dto.admin.UpdateCampaignAdminRequest;
import com.viralground.backend.dto.admin.UpdateCampaignFeaturedRequest;
import com.viralground.backend.dto.admin.UpdateCampaignVisibilityRequest;
import com.viralground.backend.dto.admin.UpdateMemberStatusRequest;
import com.viralground.backend.dto.campaign.CampaignCreateRequest;
import com.viralground.backend.entity.Campaign;
import com.viralground.backend.service.AdminService;
import com.viralground.backend.service.ContactService;
import com.viralground.backend.service.EscrowService;
import com.viralground.backend.service.ReelAnalyticsService;
import com.viralground.backend.service.ReelMetricSyncService;
import com.viralground.backend.service.ReelMetricSyncService.SyncResult;
import com.viralground.backend.payment.PaymentActor;
import com.viralground.backend.logging.AuditAction;
import com.viralground.backend.logging.AuditService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;
    private final EscrowService escrowService;
    private final ContactService contactService;
    private final ReelAnalyticsService reelAnalyticsService;
    private final ReelMetricSyncService reelMetricSyncService;
    private final AuditService auditService;

    // ── 대시보드 KPI ──────────────────────────────────

    @GetMapping("/dashboard/kpi")
    ResponseEntity<Map<String, Object>> getKpi() {
        return ResponseEntity.ok(adminService.getKpi());
    }

    // ── 릴스 분석 대시보드 ──────────────────────────────────

    @GetMapping("/reel-analytics")
    ResponseEntity<ReelAnalyticsResponse> getReelAnalytics() {
        return ResponseEntity.ok(reelAnalyticsService.getDashboard());
    }

    /** 연동 크리에이터 릴스 지표를 즉시 동기화(스냅샷 적재). 대시보드 "지금 동기화" 버튼. */
    @PostMapping("/reel-analytics/sync")
    ResponseEntity<Map<String, Integer>> syncReelAnalytics(
            @AuthenticationPrincipal AuthUser authUser) {
        SyncResult result = reelMetricSyncService.syncAll();
        auditService.record(authUser, AuditAction.METRICS_SYNC_TRIGGERED,
                "instagramMetrics", null, "SUCCESS",
                "synced=" + result.synced() + ",failed=" + result.failed());
        return ResponseEntity.ok(Map.of("synced", result.synced(), "failed", result.failed()));
    }

    // ── 회원 관리 ──────────────────────────────────

    @GetMapping("/members")
    ResponseEntity<Map<String, Object>> getMembers(
            @RequestParam(required = false, defaultValue = "ALL") String status,
            @RequestParam(required = false) String search) {
        return ResponseEntity.ok(adminService.getMembers(status, search));
    }

    @GetMapping("/members/{id}")
    ResponseEntity<MemberDetailResponse> getMember(@PathVariable Integer id) {
        return ResponseEntity.ok(adminService.getMember(id));
    }

    @DeleteMapping("/members/{id}")
    ResponseEntity<Void> deleteMember(@PathVariable Integer id,
                                      @AuthenticationPrincipal AuthUser authUser) {
        adminService.deleteMember(id, authUser.getId());
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/members/{id}/status")
    ResponseEntity<Map<String, String>> updateMemberStatus(@PathVariable Integer id,
                                                           @Valid @RequestBody UpdateMemberStatusRequest req,
                                                           @AuthenticationPrincipal AuthUser authUser) {
        adminService.updateMemberStatus(id, req.getStatus());
        auditService.record(authUser, AuditAction.MEMBER_STATUS_CHANGED,
                "member", id, "SUCCESS", req.getStatus().name());
        return ResponseEntity.ok(Map.of("message", "상태가 변경되었습니다."));
    }

    // ── 캠페인 관리 ──────────────────────────────────

    @GetMapping("/campaigns")
    ResponseEntity<Map<String, Object>> getCampaigns(
            @RequestParam(required = false, defaultValue = "ALL") String status) {
        List<Map<String, Object>> campaigns = adminService.getCampaigns(status);
        return ResponseEntity.ok(Map.of("campaigns", campaigns));
    }

    @GetMapping("/campaigns/{id}")
    ResponseEntity<CampaignDetailResponse> getCampaign(@PathVariable Integer id) {
        return ResponseEntity.ok(adminService.getCampaign(id));
    }

    @PostMapping("/campaigns")
    ResponseEntity<Map<String, Object>> createCampaign(
            @Valid @RequestBody CampaignCreateRequest req,
            @AuthenticationPrincipal AuthUser authUser) {
        Campaign c = adminService.createCampaign(req, authUser.getId());
        auditService.record(authUser, AuditAction.CAMPAIGN_STATE_CHANGED,
                "campaign", c.getId(), "SUCCESS", "CREATED");
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("id", c.getId(), "title", c.getTitle()));
    }

    @PutMapping("/campaigns/{id}")
    ResponseEntity<Map<String, String>> updateCampaign(@PathVariable Integer id,
                                                       @Valid @RequestBody UpdateCampaignAdminRequest req,
                                                       @AuthenticationPrincipal AuthUser authUser) {
        adminService.updateCampaign(id, req);
        auditService.record(authUser, AuditAction.CAMPAIGN_STATE_CHANGED,
                "campaign", id, "SUCCESS", "UPDATED");
        return ResponseEntity.ok(Map.of("message", "캠페인이 수정되었습니다."));
    }

    @DeleteMapping("/campaigns/{id}")
    ResponseEntity<Void> deleteCampaign(@PathVariable Integer id,
                                        @AuthenticationPrincipal AuthUser authUser) {
        adminService.deleteCampaign(id);
        auditService.record(authUser, AuditAction.CAMPAIGN_STATE_CHANGED,
                "campaign", id, "SUCCESS", "DELETED");
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/campaigns/{id}/visibility")
    ResponseEntity<Map<String, String>> setCampaignVisibility(
            @PathVariable Integer id,
            @Valid @RequestBody UpdateCampaignVisibilityRequest req,
            @AuthenticationPrincipal AuthUser authUser) {
        adminService.setCampaignVisibility(id, req.getHidden());
        auditService.record(authUser, AuditAction.CAMPAIGN_STATE_CHANGED,
                "campaign", id, "SUCCESS", req.getHidden() ? "HIDDEN" : "VISIBLE");
        return ResponseEntity.ok(Map.of(
                "message", req.getHidden() ? "캠페인이 숨김 처리되었습니다." : "캠페인이 다시 노출됩니다."));
    }

    @PatchMapping("/campaigns/{id}/featured")
    ResponseEntity<Map<String, String>> setCampaignFeatured(
            @PathVariable Integer id,
            @Valid @RequestBody UpdateCampaignFeaturedRequest req,
            @AuthenticationPrincipal AuthUser authUser) {
        adminService.setCampaignFeatured(id, req.getFeatured());
        auditService.record(authUser, AuditAction.CAMPAIGN_STATE_CHANGED,
                "campaign", id, "SUCCESS", req.getFeatured() ? "FEATURED" : "UNFEATURED");
        return ResponseEntity.ok(Map.of(
                "message", req.getFeatured()
                        ? "대표 캠페인으로 지정되었습니다." : "대표 캠페인 지정이 해제되었습니다."));
    }

    // ── 지원 관리 ──────────────────────────────────

    @PatchMapping("/applications/{id}")
    ResponseEntity<Map<String, String>> updateApplication(@PathVariable Integer id,
                                                          @Valid @RequestBody UpdateApplicationStatusRequest req,
                                                          @AuthenticationPrincipal AuthUser authUser) {
        adminService.updateApplication(id, req, authUser.getId());
        auditService.record(authUser, AuditAction.CAMPAIGN_STATE_CHANGED,
                "campaignApplication", id, "SUCCESS", req.getStatus().name());
        return ResponseEntity.ok(Map.of("message", "지원 상태가 변경되었습니다."));
    }

    // ── 예치금 관리 ──────────────────────────────────

    @GetMapping("/escrow/pending")
    ResponseEntity<Map<String, Object>> getPendingEscrow() {
        return ResponseEntity.ok(Map.of("campaigns", adminService.getPendingEscrowCampaigns()));
    }

    @PostMapping("/campaigns/{id}/escrow/confirm")
    ResponseEntity<Map<String, String>> confirmEscrow(
            @PathVariable Integer id,
            @AuthenticationPrincipal AuthUser authUser,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Operation-Reason", defaultValue = "관리자 입금 확인") String reason) {
        escrowService.confirmDeposit(id, PaymentActor.admin(authUser.getId()), reason, idempotencyKey);
        auditService.record(authUser, AuditAction.PAYMENT_STATE_CHANGED,
                "campaign", id, "SUCCESS", reason);
        return ResponseEntity.ok(Map.of("message", "예치금 입금이 확인되었습니다."));
    }

    @PostMapping("/campaigns/{id}/escrow/reject")
    ResponseEntity<Map<String, String>> rejectEscrow(
            @PathVariable Integer id,
            @AuthenticationPrincipal AuthUser authUser,
            @RequestHeader(value = "X-Operation-Reason", defaultValue = "관리자 입금 확인 반려") String reason) {
        escrowService.rejectDeposit(id, PaymentActor.admin(authUser.getId()), reason);
        auditService.record(authUser, AuditAction.PAYMENT_STATE_CHANGED,
                "campaign", id, "SUCCESS", reason);
        return ResponseEntity.ok(Map.of("message", "입금 확인이 반려되었습니다."));
    }

    @PostMapping("/campaigns/{id}/escrow/force-complete")
    ResponseEntity<Map<String, String>> forceCompleteEscrow(
            @PathVariable Integer id,
            @AuthenticationPrincipal AuthUser authUser,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Operation-Reason", defaultValue = "관리자 강제 입금 확인") String reason) {
        escrowService.forceConfirmDeposit(id, PaymentActor.admin(authUser.getId()), reason, idempotencyKey);
        auditService.record(authUser, AuditAction.PAYMENT_STATE_CHANGED,
                "campaign", id, "SUCCESS", reason);
        return ResponseEntity.ok(Map.of("message", "예치금이 완료 처리되었습니다."));
    }

    // ── 상담 신청 관리 ──────────────────────────────────

    @GetMapping("/contacts")
    ResponseEntity<Map<String, Object>> getContacts() {
        List<ContactRequestResponse> contacts = contactService.listAll().stream()
                .map(ContactRequestResponse::from)
                .toList();
        return ResponseEntity.ok(Map.of("contacts", contacts));
    }
}
