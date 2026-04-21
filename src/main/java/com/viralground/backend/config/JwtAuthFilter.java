package com.viralground.backend.config;

import com.viralground.backend.entity.Role;
import com.viralground.backend.service.JwtService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            Claims claims = jwtService.parseToken(token);
            if (claims != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                try {
                    AuthUser authUser = new AuthUser(
                            Integer.parseInt(claims.getSubject()),
                            claims.get("email", String.class),
                            Role.valueOf(claims.get("role", String.class)),
                            claims.get("name", String.class)
                    );
                    var auth = new UsernamePasswordAuthenticationToken(
                            authUser, null,
                            List.of(new SimpleGrantedAuthority("ROLE_" + authUser.getRole().name()))
                    );
                    SecurityContextHolder.getContext().setAuthentication(auth);
                } catch (Exception ignored) {}
            }
        }
        filterChain.doFilter(request, response);
    }
}
