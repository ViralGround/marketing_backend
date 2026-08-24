package com.viralground.backend.instagram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/** 운영 환경에서 mock provider로 조용히 기동되는 사고를 막는다. */
@Component
public class InstagramProviderSafetyValidator implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(InstagramProviderSafetyValidator.class);
    private static final Set<String> PROTECTED_ENVIRONMENTS =
            Set.of("prod", "production", "preproduction", "staging");

    private final Environment environment;

    public InstagramProviderSafetyValidator(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        String provider = environment.getProperty("instagram.provider", "")
                .trim().toLowerCase(Locale.ROOT);
        String appEnvironment = environment.getProperty("app.environment", "development").trim();
        String providerEnvironment = environment.getProperty(
                "instagram.environment", "development").trim();
        if (!appEnvironment.equals(providerEnvironment)) {
            throw new IllegalStateException("INSTAGRAM_ENV must exactly match APP_ENV");
        }
        if (!Set.of("disabled", "mock", "meta").contains(provider)) {
            throw new IllegalStateException("INSTAGRAM_PROVIDER는 disabled, mock 또는 meta여야 합니다");
        }
        boolean guardedCloneProcess = environment.getProperty(
                "app.migration-runner.enabled", Boolean.class, false)
                || environment.getProperty(
                "app.exact-compatibility.enabled", Boolean.class, false);
        if (PROTECTED_ENVIRONMENTS.contains(appEnvironment) && guardedCloneProcess
                && !"disabled".equals(provider)) {
            throw new IllegalStateException(
                    "guarded clone process requires INSTAGRAM_PROVIDER=disabled");
        }
        if (PROTECTED_ENVIRONMENTS.contains(appEnvironment) && !guardedCloneProcess
                && !"meta".equals(provider)) {
            throw new IllegalStateException(
                    "보호 환경은 기능 플래그와 무관하게 권한 철회를 위해 INSTAGRAM_PROVIDER=meta가 필수입니다");
        }
        log.info("event=instagram_provider_ready provider={} environment={}", provider, appEnvironment);
    }
}
