package com.viralground.backend.controller;

import com.viralground.backend.config.AuthUser;
import com.viralground.backend.dto.campaign.ApplicationResponse;
import com.viralground.backend.dto.campaign.CampaignResponse;
import com.viralground.backend.dto.campaign.SubmitWorkRequest;
import com.viralground.backend.dto.profile.UpdateProfileRequest;
import com.viralground.backend.entity.Role;
import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import com.viralground.backend.service.CampaignService;
import com.viralground.backend.service.ProfileService;
import com.viralground.backend.service.AccountWithdrawalService;
import com.viralground.backend.service.AuthCookieService;
import com.viralground.backend.logging.AuditAction;
import com.viralground.backend.logging.AuditService;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class CampaignController {

    private final CampaignService campaignService;
    private final ProfileService profileService;
    private final AuditService auditService;
    private final AccountWithdrawalService accountWithdrawalService;
    private final AuthCookieService authCookieService;

    @GetMapping("/campaigns")
    public ResponseEntity<Map<String, Object>> getCampaigns(
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String search,
            @AuthenticationPrincipal AuthUser authUser) {
        requireCreator(authUser);
        List<CampaignResponse> campaigns = campaignService.getOpenCampaigns(sort, search, authUser.getId());
        return ResponseEntity.ok(Map.of("campaigns", campaigns));
    }

    @GetMapping("/campaigns/{id}")
    public ResponseEntity<CampaignResponse> getCampaign(
            @PathVariable Integer id,
            @AuthenticationPrincipal AuthUser authUser) {
        requireCreator(authUser);
        return ResponseEntity.ok(campaignService.getCampaign(id, authUser.getId()));
    }

    @PostMapping("/campaigns/{id}/apply")
    public ResponseEntity<Map<String, Object>> apply(
            @PathVariable Integer id,
            @RequestBody(required = false) Map<String, String> body,
            @AuthenticationPrincipal AuthUser authUser) {
        requireCreator(authUser);
        String message = body != null ? body.get("message") : null;
        var app = campaignService.apply(id, authUser.getId(), message);
        auditService.record(authUser, AuditAction.CAMPAIGN_APPLIED,
                "campaignApplication", app.getId(), "SUCCESS", null);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("id", app.getId(), "status", app.getStatus().name()));
    }

    @GetMapping("/me/applications")
    public ResponseEntity<Map<String, Object>> getMyApplications(
            @RequestParam(required = false, defaultValue = "ALL") String status,
            @AuthenticationPrincipal AuthUser authUser) {
        requireCreator(authUser);
        List<ApplicationResponse> apps = campaignService.getMyApplications(authUser.getId(), status);
        return ResponseEntity.ok(Map.of("applications", apps));
    }

    @PostMapping("/me/applications/{id}/submit")
    public ResponseEntity<Map<String, String>> submitWork(
            @PathVariable Integer id,
            @RequestBody SubmitWorkRequest body,
            @AuthenticationPrincipal AuthUser authUser) {
        requireCreator(authUser);
        String trackingMode = campaignService.submitWork(id, authUser.getId(), body);
        auditService.record(authUser, AuditAction.CONTENT_SUBMITTED,
                "campaignApplication", id, "SUCCESS", trackingMode);
        return ResponseEntity.ok(Map.of(
                "message", "제출이 완료되었습니다.",
                "trackingMode", trackingMode));
    }

    @DeleteMapping("/me")
    public ResponseEntity<Void> deleteAccount(
            @AuthenticationPrincipal AuthUser authUser,
            HttpServletResponse response) {
        requireCreator(authUser);
        accountWithdrawalService.withdrawCreator(authUser.getId());
        auditService.record(authUser, AuditAction.MEMBER_WITHDRAWN,
                "member", authUser.getId(), "SUCCESS", "self-service");
        authCookieService.clear(response);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me/stats")
    public ResponseEntity<Map<String, Object>> getStats(@AuthenticationPrincipal AuthUser authUser) {
        requireCreator(authUser);
        return ResponseEntity.ok(campaignService.getStats(authUser.getId()));
    }

    @GetMapping("/profile")
    public ResponseEntity<Map<String, Object>> getProfile(@AuthenticationPrincipal AuthUser authUser) {
        requireCreator(authUser);
        return ResponseEntity.ok(profileService.getProfile(authUser.getId()));
    }

    @PostMapping("/profile")
    public ResponseEntity<Map<String, String>> updateProfile(
            @Valid @RequestBody UpdateProfileRequest req,
            @AuthenticationPrincipal AuthUser authUser) {
        requireCreator(authUser);
        profileService.updateProfile(authUser.getId(), req);
        auditService.record(authUser, AuditAction.PROFILE_CHANGED,
                "creatorProfile", authUser.getId(), "SUCCESS",
                Boolean.TRUE.equals(req.getPublicProfileOptIn())
                        ? "PUBLIC_PROFILE_ENABLED" : "PUBLIC_PROFILE_DISABLED");
        return ResponseEntity.ok(Map.of("message", "프로필이 저장되었습니다."));
    }

    private void requireCreator(AuthUser authUser) {
        if (authUser == null || authUser.getRole() != Role.CREATOR) {
            throw new AppException(ErrorCode.FORBIDDEN);
        }
    }
}
