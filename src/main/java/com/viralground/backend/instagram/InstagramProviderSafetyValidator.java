package com.viralground.backend.instagram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/** 운영 환경에서 mock provider로 조용히 기동되는 사고를 막는다. */
@Component
public class InstagramProviderSafetyValidator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(InstagramProviderSafetyValidator.class);
    private static final Set<String> PRODUCTION_ENVIRONMENTS = Set.of("prod", "production");

    private final String provider;
    private final String environment;

    public InstagramProviderSafetyValidator(
            @Value("${instagram.provider}") String provider,
            @Value("${instagram.environment:development}") String environment) {
        this.provider = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        this.environment = environment == null ? "" : environment.trim().toLowerCase(Locale.ROOT);
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!Set.of("mock", "meta").contains(provider)) {
            throw new IllegalStateException("INSTAGRAM_PROVIDER는 mock 또는 meta여야 합니다");
        }
        if (PRODUCTION_ENVIRONMENTS.contains(environment) && !"meta".equals(provider)) {
            throw new IllegalStateException("운영 환경에서는 INSTAGRAM_PROVIDER=meta가 필수입니다");
        }
        log.info("event=instagram_provider_ready provider={} environment={}", provider, environment);
    }
}
