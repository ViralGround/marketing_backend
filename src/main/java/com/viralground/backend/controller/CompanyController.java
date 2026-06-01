package com.viralground.backend.controller;

import com.viralground.backend.config.AuthUser;
import com.viralground.backend.dto.company.CompanyApplicationActionRequest;
import com.viralground.backend.dto.company.CompanyCampaignCreateRequest;
import com.viralground.backend.dto.company.CompanyCampaignResponse;
import com.viralground.backend.dto.company.CompanyCampaignUpdateRequest;
import com.viralground.backend.dto.company.CompanyProfileResponse;
import com.viralground.backend.dto.company.UpdateCompanyProfileRequest;
import com.viralground.backend.entity.Role;
import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import com.viralground.backend.service.CompanyService;
import com.viralground.backend.service.EscrowService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/company")
@RequiredArgsConstructor
public class CompanyController {

    private final CompanyService companyService;
    private final EscrowService escrowService;

    @GetMapping("/dashboard")
    public ResponseEntity<Map<String, Object>> dashboard(@AuthenticationPrincipal AuthUser authUser) {
        requireCompany(authUser);
        return ResponseEntity.ok(companyService.getDashboardSummary(authUser.getId()));
    }

    @GetMapping("/profile")
    public ResponseEntity<CompanyProfileResponse> getProfile(@AuthenticationPrincipal AuthUser authUser) {
        requireCompany(authUser);
        return ResponseEntity.ok(companyService.getMyProfile(authUser.getId()));
    }

    @PatchMapping("/profile")
    public ResponseEntity<Map<String, String>> updateProfile(
            @Valid @RequestBody UpdateCompanyProfileRequest req,
            @AuthenticationPrincipal AuthUser authUser) {
        requireCompany(authUser);
        companyService.updateMyProfile(authUser.getId(), req);
        return ResponseEntity.ok(Map.of("message", "회사 정보가 저장되었습니다."));
    }

    @GetMapping("/campaigns")
    public ResponseEntity<Map<String, Object>> listCampaigns(@AuthenticationPrincipal AuthUser authUser) {
        requireCompany(authUser);
        return ResponseEntity.ok(Map.of("campaigns", companyService.listCampaigns(authUser.getId())));
    }

    @PostMapping("/campaigns")
    public ResponseEntity<CompanyCampaignResponse> createCampaign(
            @Valid @RequestBody CompanyCampaignCreateRequest req,
            @AuthenticationPrincipal AuthUser authUser) {
        requireCompany(authUser);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(companyService.createCampaign(authUser.getId(), req));
    }

    @GetMapping("/campaigns/{id}")
    public ResponseEntity<CompanyCampaignResponse> getCampaign(
            @PathVariable Integer id,
            @AuthenticationPrincipal AuthUser authUser) {
        requireCompany(authUser);
        return ResponseEntity.ok(companyService.getCampaign(id, authUser.getId()));
    }

    @PostMapping("/campaigns/{id}/deposit-request")
    public ResponseEntity<Map<String, String>> requestDeposit(
            @PathVariable Integer id,
            @AuthenticationPrincipal AuthUser authUser) {
        requireCompany(authUser);
        escrowService.requestDeposit(id, authUser.getId());
        return ResponseEntity.ok(Map.of("message", "입금 확인을 요청했습니다. 관리자 검토 후 모집이 시작됩니다."));
    }

    @PatchMapping("/campaigns/{id}")
    public ResponseEntity<CompanyCampaignResponse> updateCampaign(
            @PathVariable Integer id,
            @RequestBody CompanyCampaignUpdateRequest req,
            @AuthenticationPrincipal AuthUser authUser) {
        requireCompany(authUser);
        return ResponseEntity.ok(companyService.updateCampaign(id, authUser.getId(), req));
    }

    @DeleteMapping("/campaigns/{id}")
    public ResponseEntity<Void> deleteCampaign(
            @PathVariable Integer id,
            @AuthenticationPrincipal AuthUser authUser) {
        requireCompany(authUser);
        companyService.deleteCampaign(id, authUser.getId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/campaigns/{id}/cancel")
    public ResponseEntity<Map<String, String>> cancelCampaign(
            @PathVariable Integer id,
            @AuthenticationPrincipal AuthUser authUser) {
        requireCompany(authUser);
        companyService.cancelCampaign(id, authUser.getId());
        return ResponseEntity.ok(Map.of("message", "캠페인이 취소되었습니다."));
    }

    @PatchMapping("/applications/{id}")
    public ResponseEntity<Map<String, String>> manageApplication(
            @PathVariable Integer id,
            @Valid @RequestBody CompanyApplicationActionRequest req,
            @AuthenticationPrincipal AuthUser authUser) {
        requireCompany(authUser);
        companyService.manageApplication(id, authUser.getId(), req);
        return ResponseEntity.ok(Map.of("message", "지원 상태가 변경되었습니다."));
    }

    private void requireCompany(AuthUser authUser) {
        if (authUser == null || authUser.getRole() != Role.COMPANY) {
            throw new AppException(ErrorCode.FORBIDDEN);
        }
    }
}
