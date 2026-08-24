package com.viralground.backend.config;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/** readiness 전용: DB가 연결돼도 적용되지 않은 Flyway migration이 있으면 DOWN으로 판정한다. */
@Component("flywaySchema")
public class FlywaySchemaHealthIndicator implements HealthIndicator {

    private final ObjectProvider<Flyway> flywayProvider;

    public FlywaySchemaHealthIndicator(ObjectProvider<Flyway> flywayProvider) {
        this.flywayProvider = flywayProvider;
    }

    @Override
    public Health health() {
        try {
            Flyway flyway = flywayProvider.getIfAvailable();
            if (flyway == null) {
                return Health.up().withDetail("schemaValidation", "disabled").build();
            }
            var validation = flyway.validateWithResult();
            if (!validation.validationSuccessful) {
                return Health.down()
                        .withDetail("reason", "validation_failed")
                        .withDetail("invalidMigrationCount", validation.invalidMigrations.size())
                        .build();
            }
            var info = flyway.info();
            var pending = info.pending();
            var current = info.current();
            long failed = Arrays.stream(info.all())
                    .filter(migration -> migration.getState().isFailed())
                    .count();
            if (failed > 0) {
                return Health.down()
                        .withDetail("reason", "failed_migrations")
                        .withDetail("failedCount", failed)
                        .build();
            }
            if (pending.length > 0) {
                return Health.down()
                        .withDetail("reason", "pending_migrations")
                        .withDetail("pendingCount", pending.length)
                        .build();
            }
            return Health.up()
                    .withDetail("schemaVersion", current == null || current.getVersion() == null
                            ? "none" : current.getVersion().getVersion())
                    .build();
        } catch (RuntimeException failure) {
            return Health.down()
                    .withDetail("reason", "flyway_validation_unavailable")
                    .withDetail("errorType", failure.getClass().getSimpleName())
                    .build();
        }
    }
}
