package com.viralground.backend.controller;

import com.viralground.backend.config.AuthUser;
import com.viralground.backend.dto.campaign.ApplicationResponse;
import com.viralground.backend.dto.campaign.CampaignResponse;
import com.viralground.backend.dto.campaign.SubmitWorkRequest;
import com.viralground.backend.dto.profile.UpdateProfileRequest;
import com.viralground.backend.service.CampaignService;
import com.viralground.backend.service.ProfileService;
import jakarta.validation.Valid;
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

    @GetMapping("/campaigns")
    public ResponseEntity<Map<String, Object>> getCampaigns(
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String search,
            @AuthenticationPrincipal AuthUser authUser) {
        List<CampaignResponse> campaigns = campaignService.getOpenCampaigns(sort, search, authUser.getId());
        return ResponseEntity.ok(Map.of("campaigns", campaigns));
    }

    @GetMapping("/campaigns/{id}")
    public ResponseEntity<CampaignResponse> getCampaign(
            @PathVariable Integer id,
            @AuthenticationPrincipal AuthUser authUser) {
        return ResponseEntity.ok(campaignService.getCampaign(id, authUser.getId()));
    }

    @PostMapping("/campaigns/{id}/apply")
    public ResponseEntity<Map<String, Object>> apply(
            @PathVariable Integer id,
            @RequestBody(required = false) Map<String, String> body,
            @AuthenticationPrincipal AuthUser authUser) {
        String message = body != null ? body.get("message") : null;
        var app = campaignService.apply(id, authUser.getId(), message);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("id", app.getId(), "status", app.getStatus().name()));
    }

    @GetMapping("/me/applications")
    public ResponseEntity<Map<String, Object>> getMyApplications(
            @RequestParam(required = false, defaultValue = "ALL") String status,
            @AuthenticationPrincipal AuthUser authUser) {
        List<ApplicationResponse> apps = campaignService.getMyApplications(authUser.getId(), status);
        return ResponseEntity.ok(Map.of("applications", apps));
    }

    @PostMapping("/me/applications/{id}/submit")
    public ResponseEntity<Map<String, String>> submitWork(
            @PathVariable Integer id,
            @RequestBody SubmitWorkRequest body,
            @AuthenticationPrincipal AuthUser authUser) {
        campaignService.submitWork(id, authUser.getId(), body);
        return ResponseEntity.ok(Map.of("message", "제출이 완료되었습니다."));
    }

    @DeleteMapping("/me")
    public ResponseEntity<Void> deleteAccount(@AuthenticationPrincipal AuthUser authUser) {
        campaignService.deleteAccount(authUser.getId());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me/stats")
    public ResponseEntity<Map<String, Object>> getStats(@AuthenticationPrincipal AuthUser authUser) {
        return ResponseEntity.ok(campaignService.getStats(authUser.getId()));
    }

    @GetMapping("/profile")
    public ResponseEntity<Map<String, Object>> getProfile(@AuthenticationPrincipal AuthUser authUser) {
        return ResponseEntity.ok(profileService.getProfile(authUser.getId()));
    }

    @PostMapping("/profile")
    public ResponseEntity<Map<String, String>> updateProfile(
            @Valid @RequestBody UpdateProfileRequest req,
            @AuthenticationPrincipal AuthUser authUser) {
        profileService.updateProfile(authUser.getId(), req);
        return ResponseEntity.ok(Map.of("message", "프로필이 저장되었습니다."));
    }
}
