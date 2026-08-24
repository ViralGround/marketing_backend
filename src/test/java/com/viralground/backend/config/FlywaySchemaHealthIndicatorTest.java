package com.viralground.backend.config;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.output.ValidateResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FlywaySchemaHealthIndicatorTest {

    @Test
    void checksumOrValidationMismatchIsDown() {
        Flyway flyway = mock(Flyway.class);
        when(flyway.validateWithResult()).thenReturn(validation(false));

        var health = indicator(flyway).health();

        assertThat(health.getStatus().getCode()).isEqualTo("DOWN");
        assertThat(health.getDetails()).containsEntry("reason", "validation_failed");
    }

    @Test
    void failedMigrationIsDownEvenWithoutPendingMigrations() {
        Flyway flyway = mock(Flyway.class);
        MigrationInfoService info = mock(MigrationInfoService.class);
        MigrationInfo failed = mock(MigrationInfo.class);
        when(failed.getState()).thenReturn(MigrationState.FAILED);
        when(flyway.validateWithResult()).thenReturn(validation(true));
        when(flyway.info()).thenReturn(info);
        when(info.pending()).thenReturn(new MigrationInfo[0]);
        when(info.all()).thenReturn(new MigrationInfo[]{failed});

        var health = indicator(flyway).health();

        assertThat(health.getStatus().getCode()).isEqualTo("DOWN");
        assertThat(health.getDetails()).containsEntry("reason", "failed_migrations");
    }

    @Test
    void pendingMigrationIsDown() {
        Flyway flyway = mock(Flyway.class);
        MigrationInfoService info = mock(MigrationInfoService.class);
        when(flyway.validateWithResult()).thenReturn(validation(true));
        when(flyway.info()).thenReturn(info);
        when(info.pending()).thenReturn(new MigrationInfo[]{mock(MigrationInfo.class)});
        when(info.all()).thenReturn(new MigrationInfo[0]);

        var health = indicator(flyway).health();

        assertThat(health.getStatus().getCode()).isEqualTo("DOWN");
        assertThat(health.getDetails()).containsEntry("reason", "pending_migrations");
    }

    @SuppressWarnings("unchecked")
    private FlywaySchemaHealthIndicator indicator(Flyway flyway) {
        ObjectProvider<Flyway> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(flyway);
        return new FlywaySchemaHealthIndicator(provider);
    }

    private ValidateResult validation(boolean successful) {
        return new ValidateResult(
                "11.7.2", "test", null, successful, 0, List.of(), List.of());
    }
}
