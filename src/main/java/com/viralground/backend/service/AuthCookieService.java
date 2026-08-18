package com.viralground.backend.service;

import com.viralground.backend.dto.auth.TokenResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/** Centralizes the exact attributes used when issuing and expiring auth cookies. */
@Component
public class AuthCookieService {

    private final boolean secure;
    private final String domain;
    private final String sameSite;

    public AuthCookieService(
            @Value("${auth.cookie.secure:false}") boolean secure,
            @Value("${auth.cookie.domain:}") String domain,
            @Value("${auth.cookie.same-site:Lax}") String sameSite) {
        this.secure = secure;
        this.domain = domain;
        this.sameSite = sameSite;
    }

    public void write(HttpServletResponse response, TokenResponse tokens) {
        response.addHeader(HttpHeaders.SET_COOKIE,
                cookieHeader("access_token", tokens.getAccessToken(), 3600));
        response.addHeader(HttpHeaders.SET_COOKIE,
                cookieHeader("refresh_token", tokens.getRefreshToken(), 604800));
    }

    public void clear(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookieHeader("access_token", "", 0));
        response.addHeader(HttpHeaders.SET_COOKIE, cookieHeader("refresh_token", "", 0));
    }

    private String cookieHeader(String name, String value, long maxAge) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(name, value)
                .path("/")
                .maxAge(maxAge)
                .httpOnly(true)
                .secure(secure)
                .sameSite(sameSite);
        if (!domain.isBlank()) {
            builder.domain(domain);
        }
        return builder.build().toString();
    }
}
