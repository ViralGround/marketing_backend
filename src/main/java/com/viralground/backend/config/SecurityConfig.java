package com.viralground.backend.config;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    @Value("${cors.allowed-origins}")
    private String allowedOrigins;

    @Value("${auth.cookie.secure:false}")
    private boolean cookieSecure;

    @Value("${auth.cookie.domain:}")
    private String cookieDomain;

    @Value("${auth.cookie.same-site:Lax}")
    private String cookieSameSite;

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> {
                    CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
                    repository.setCookieCustomizer(cookie -> {
                        cookie.sameSite(cookieSameSite)
                                .secure(cookieSecure)
                                .path("/");
                        if (!cookieDomain.isBlank()) cookie.domain(cookieDomain);
                    });
                    CsrfTokenRequestAttributeHandler handler = new CsrfTokenRequestAttributeHandler();
                    handler.setCsrfRequestAttributeName(null);
                    csrf.csrfTokenRepository(repository)
                            .csrfTokenRequestHandler(handler)
                            .ignoringRequestMatchers(
                                    "/auth/signup", "/auth/signup/company",
                                    "/auth/email/**", "/auth/password/**",
                                    "/instagram/meta/webhook",
                                    "/files/upload/**", "/contact");
                })
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        .requestMatchers("/auth/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/instagram/meta/oauth/callback").permitAll()
                        .requestMatchers(HttpMethod.GET, "/instagram/meta/webhook").permitAll()
                        .requestMatchers(HttpMethod.POST, "/instagram/meta/webhook").permitAll()
                        // 랜딩 페이지 상담신청 — 비인증 공개. 컨트롤러에서 동의·honeypot·rate-limit 적용.
                        .requestMatchers(HttpMethod.POST, "/contact").permitAll()
                        // 서명 URL 기반 접근 — JWT 불필요. 서명 검증은 FileStorage 구현체가 담당.
                        .requestMatchers(HttpMethod.PUT, "/files/upload/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/files/**").permitAll()
                        // 랜딩 페이지 공개 조회(대표 캠페인·회사 소개·크리에이터 풀) — 비로그인 노출.
                        .requestMatchers(HttpMethod.GET, "/landing/**").permitAll()
                        // 크리에이터 공개 포트폴리오·리뷰 — /creators 풀 목록에서 진입하는 공개 상세.
                        .requestMatchers(HttpMethod.GET, "/creators/*/portfolio").permitAll()
                        .requestMatchers(HttpMethod.GET, "/creators/*/reviews").permitAll()
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        // 콤마 구분 origin 을 파싱하되 각 항목의 공백과 감싼 따옴표를 제거한다.
        // (.env 에 CORS_ORIGINS="a,b" 처럼 따옴표째 들어와도 안전하게 매칭되도록.)
        config.setAllowedOrigins(
                Arrays.stream(allowedOrigins.split(","))
                        .map(o -> o.trim().replaceAll("^\"|\"$", ""))
                        .filter(o -> !o.isBlank())
                        .toList());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        // 브라우저가 preflight(OPTIONS) 응답을 1시간 캐싱하도록 지시.
        // 도쿄 DB ↔ 싱가포르 Railway 환경에서 매 API 호출당 ~500ms 추가 왕복이
        // 사라진다. Chromium 계열의 OPTIONS 캐시 상한은 2시간(7200) 이므로
        // 그 안에서 1시간을 선택.
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
