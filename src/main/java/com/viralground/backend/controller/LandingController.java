package com.viralground.backend.controller;

import com.viralground.backend.dto.landing.CompanyPublicResponse;
import com.viralground.backend.dto.landing.FeaturedCampaignResponse;
import com.viralground.backend.service.LandingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 랜딩 페이지(비로그인) 공개 API. SecurityConfig 에서 {@code GET /landing/**} 를 permitAll 로 연다.
 */
@RestController
@RequestMapping("/landing")
@RequiredArgsConstructor
public class LandingController {

    private final LandingService landingService;

    @GetMapping("/featured-campaigns")
    ResponseEntity<Map<String, List<FeaturedCampaignResponse>>> getFeaturedCampaigns() {
        return ResponseEntity.ok(Map.of("campaigns", landingService.getFeaturedCampaigns()));
    }

    @GetMapping("/companies/{memberId}")
    ResponseEntity<CompanyPublicResponse> getCompany(@PathVariable Integer memberId) {
        return ResponseEntity.ok(landingService.getCompanyPublic(memberId));
    }
}
