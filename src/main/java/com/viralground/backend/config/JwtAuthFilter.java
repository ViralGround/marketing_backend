package com.viralground.backend.config;

import com.viralground.backend.service.JwtService;
import com.viralground.backend.repository.MemberRepository;
import com.viralground.backend.entity.MemberStatus;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final MemberRepository memberRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String token = resolveAccessToken(request);
        if (token != null) {
            Claims claims = jwtService.parseToken(token);
            if (claims != null && jwtService.isTokenType(claims, "access")
                    && SecurityContextHolder.getContext().getAuthentication() == null) {
                try {
                    int memberId = Integer.parseInt(claims.getSubject());
                    var member = memberRepository.findById(memberId).orElse(null);
                    if (member == null || member.getStatus() != MemberStatus.APPROVED
                            || !Boolean.TRUE.equals(member.getEmailVerified())) {
                        SecurityContextHolder.clearContext();
                        filterChain.doFilter(request, response);
                        return;
                    }
                    AuthUser authUser = new AuthUser(
                            memberId,
                            member.getEmail(),
                            member.getRole(),
                            member.getName()
                    );
                    var auth = new UsernamePasswordAuthenticationToken(
                            authUser, null,
                            List.of(new SimpleGrantedAuthority("ROLE_" + authUser.getRole().name()))
                    );
                    SecurityContextHolder.getContext().setAuthentication(auth);
                } catch (NumberFormatException e) {
                    log.warn("event=authentication_rejected reason=invalid_subject");
                    SecurityContextHolder.clearContext();
                } catch (IllegalArgumentException | NullPointerException e) {
                    log.warn("event=authentication_rejected reason=invalid_claims errorType={}",
                            e.getClass().getSimpleName());
                    SecurityContextHolder.clearContext();
                }
            }
        }
        filterChain.doFilter(request, response);
    }

    private String resolveAccessToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        if (request.getCookies() != null) {
            for (var cookie : request.getCookies()) {
                if ("access_token".equals(cookie.getName())) return cookie.getValue();
            }
        }
        return null;
    }
}
