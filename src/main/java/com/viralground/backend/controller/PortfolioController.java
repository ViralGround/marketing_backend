package com.viralground.backend.controller;

import com.viralground.backend.config.AuthUser;
import com.viralground.backend.service.PortfolioService;
import com.viralground.backend.entity.Role;
import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
public class PortfolioController {

    private final PortfolioService portfolioService;

    @GetMapping("/creators/{id}/portfolio")
    public ResponseEntity<Map<String, Object>> getCreatorPortfolio(
            @PathVariable Integer id,
            @AuthenticationPrincipal AuthUser authUser) {
        return ResponseEntity.ok(portfolioService.getPublicPortfolio(id));
    }

    @GetMapping("/me/portfolio")
    public ResponseEntity<Map<String, Object>> getMyPortfolio(
            @AuthenticationPrincipal AuthUser authUser) {
        if (authUser == null || authUser.getRole() != Role.CREATOR) {
            throw new AppException(ErrorCode.FORBIDDEN);
        }
        return ResponseEntity.ok(portfolioService.getPortfolio(authUser.getId()));
    }
}
