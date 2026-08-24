package com.viralground.backend.config;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** /actuator/info에 비밀정보 없이 배포 식별자와 적용 schema 버전만 공개한다. */
@Component
public class ReleaseInfoContributor implements InfoContributor {

    private final ObjectProvider<Flyway> flywayProvider;
    private final String releaseId;
    private final String gitCommitSha;
    private final String buildTime;

    public ReleaseInfoContributor(
            ObjectProvider<Flyway> flywayProvider,
            @Value("${app.release-id:local}") String releaseId,
            @Value("${app.git-commit-sha:unknown}") String gitCommitSha,
            @Value("${app.build-time:unknown}") String buildTime) {
        this.flywayProvider = flywayProvider;
        this.releaseId = releaseId;
        this.gitCommitSha = gitCommitSha;
        this.buildTime = buildTime;
    }

    @Override
    public void contribute(Info.Builder builder) {
        Map<String, Object> release = new LinkedHashMap<>();
        release.put("releaseId", releaseId);
        release.put("commitSha", gitCommitSha);
        release.put("buildTime", buildTime);
        release.put("schemaVersion", currentSchemaVersion());
        builder.withDetail("release", release);
    }

    private String currentSchemaVersion() {
        Flyway flyway = flywayProvider.getIfAvailable();
        if (flyway == null) return "unavailable";
        try {
            var current = flyway.info().current();
            return current == null || current.getVersion() == null
                    ? "none" : current.getVersion().getVersion();
        } catch (RuntimeException unavailable) {
            return "unavailable";
        }
    }
}
