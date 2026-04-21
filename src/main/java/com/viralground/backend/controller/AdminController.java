package com.viralground.backend.controller;

import com.viralground.backend.config.AuthUser;
import com.viralground.backend.dto.admin.CampaignDetailResponse;
import com.viralground.backend.dto.admin.MemberDetailResponse;
import com.viralground.backend.dto.campaign.CampaignCreateRequest;
import com.viralground.backend.entity.Campaign;
import com.viralground.backend.service.AdminService;
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
                                                           @RequestBody Map<String, String> body) {
        adminService.updateMemberStatus(id, body.get("status"));
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
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("id", c.getId(), "title", c.getTitle()));
    }

    @PutMapping("/campaigns/{id}")
    ResponseEntity<Map<String, String>> updateCampaign(@PathVariable Integer id,
                                                       @RequestBody Map<String, Object> body) {
        adminService.updateCampaign(id, body);
        return ResponseEntity.ok(Map.of("message", "캠페인이 수정되었습니다."));
    }

    @DeleteMapping("/campaigns/{id}")
    ResponseEntity<Void> deleteCampaign(@PathVariable Integer id) {
        adminService.deleteCampaign(id);
        return ResponseEntity.noContent().build();
    }

    // ── 지원 관리 ──────────────────────────────────

    @PatchMapping("/applications/{id}")
    ResponseEntity<Map<String, String>> updateApplication(@PathVariable Integer id,
                                                          @RequestBody Map<String, Object> body) {
        adminService.updateApplication(id, body);
        return ResponseEntity.ok(Map.of("message", "지원 상태가 변경되었습니다."));
    }
}
