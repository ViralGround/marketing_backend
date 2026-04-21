package com.viralground.backend.controller;

import com.viralground.backend.config.AuthUser;
import com.viralground.backend.dto.company.CompanyCampaignCreateRequest;
import com.viralground.backend.dto.company.CompanyCampaignResponse;
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

    private void requireCompany(AuthUser authUser) {
        if (authUser == null || authUser.getRole() != Role.COMPANY) {
            throw new AppException(ErrorCode.FORBIDDEN);
        }
    }
}
