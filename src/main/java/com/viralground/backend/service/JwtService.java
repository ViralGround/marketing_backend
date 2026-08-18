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
import java.time.Instant;
import java.util.UUID;

@Service
public class JwtService {

    private final SecretKey key;
    private final long accessExpirySeconds;
    private final long refreshExpirySeconds;
    private final String issuer;
    private final String audience;

    public JwtService(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.access-expiry}") long accessExpirySeconds,
            @Value("${jwt.refresh-expiry}") long refreshExpirySeconds,
            @Value("${jwt.issuer:viralground-api}") String issuer,
            @Value("${jwt.audience:viralground-web}") String audience) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessExpirySeconds = accessExpirySeconds;
        this.refreshExpirySeconds = refreshExpirySeconds;
        this.issuer = issuer;
        this.audience = audience;
    }

    public String generateAccessToken(Member member) {
        return Jwts.builder()
                .subject(member.getId().toString())
                // 역할은 Next edge routing의 UX 힌트로만 사용한다. API는 매 요청 DB 역할을 다시 확인한다.
                .claim("role", member.getRole().name())
                .claim("token_type", "access")
                .issuer(issuer)
                .audience().add(audience).and()
                .id(UUID.randomUUID().toString())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + accessExpirySeconds * 1000))
                .signWith(key)
                .compact();
    }

    public IssuedRefreshToken generateRefreshToken(Member member, String familyId) {
        String tokenId = UUID.randomUUID().toString();
        Instant expiresAt = Instant.now().plusSeconds(refreshExpirySeconds);
        String token = Jwts.builder()
                .subject(member.getId().toString())
                .claim("token_type", "refresh")
                .claim("family_id", familyId)
                .issuer(issuer)
                .audience().add(audience).and()
                .id(tokenId)
                .issuedAt(new Date())
                .expiration(Date.from(expiresAt))
                .signWith(key)
                .compact();
        return new IssuedRefreshToken(token, tokenId, familyId, expiresAt);
    }

    public Claims parseToken(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(issuer)
                    .requireAudience(audience)
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
                .issuer(issuer)
                .audience().add(audience).and()
                .id(UUID.randomUUID().toString())
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

    public boolean isTokenType(Claims claims, String type) {
        return claims != null && type.equals(claims.get("token_type", String.class));
    }

    public record IssuedRefreshToken(String token, String tokenId, String familyId, Instant expiresAt) {}
}
