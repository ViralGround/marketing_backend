package com.viralground.backend.service;

import com.viralground.backend.entity.Member;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Service
public class JwtService {

    private final SecretKey key;
    private final long accessExpirySeconds;
    private final long refreshExpirySeconds;

    public JwtService(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.access-expiry}") long accessExpirySeconds,
            @Value("${jwt.refresh-expiry}") long refreshExpirySeconds) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessExpirySeconds = accessExpirySeconds;
        this.refreshExpirySeconds = refreshExpirySeconds;
    }

    public String generateAccessToken(Member member) {
        return Jwts.builder()
                .subject(member.getId().toString())
                .claim("email", member.getEmail())
                .claim("role", member.getRole().name())
                .claim("name", member.getName())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + accessExpirySeconds * 1000))
                .signWith(key)
                .compact();
    }

    public String generateRefreshToken(Member member) {
        return Jwts.builder()
                .subject(member.getId().toString())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + refreshExpirySeconds * 1000))
                .signWith(key)
                .compact();
    }

    public Claims parseToken(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }

    private static final long EMAIL_VERIFIED_EXPIRY_SECONDS = 15 * 60;
    private static final String EMAIL_VERIFIED_PURPOSE = "signup-verified";

    public String generateEmailVerifiedToken(String email) {
        return Jwts.builder()
                .claim("email", email)
                .claim("purpose", EMAIL_VERIFIED_PURPOSE)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + EMAIL_VERIFIED_EXPIRY_SECONDS * 1000))
                .signWith(key)
                .compact();
    }

    public boolean verifyEmailVerifiedToken(String token, String expectedEmail) {
        Claims claims = parseToken(token);
        if (claims == null) return false;
        Object purpose = claims.get("purpose");
        if (!EMAIL_VERIFIED_PURPOSE.equals(purpose)) return false;
        Object email = claims.get("email");
        return expectedEmail != null && expectedEmail.equals(email);
    }
}
