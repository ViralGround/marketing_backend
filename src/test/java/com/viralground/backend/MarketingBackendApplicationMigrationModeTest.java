package com.viralground.backend;

import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketingBackendApplicationMigrationModeTest {

    @Test
    void closesContextAfterSuccessfulOneShotMigrationStartup() {
        ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
        ConfigurableEnvironment environment = mock(ConfigurableEnvironment.class);
        when(context.getEnvironment()).thenReturn(environment);
        when(environment.getProperty(
                "app.migration-runner.enabled", Boolean.class, false)).thenReturn(true);

        MarketingBackendApplication.closeCompletedMigrationRunner(context);

        verify(context).close();
    }

    @Test
    void keepsNormalServerContextRunningByDefault() {
        ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
        ConfigurableEnvironment environment = mock(ConfigurableEnvironment.class);
        when(context.getEnvironment()).thenReturn(environment);
        when(environment.getProperty(
                "app.migration-runner.enabled", Boolean.class, false)).thenReturn(false);

        MarketingBackendApplication.closeCompletedMigrationRunner(context);

        verify(context, never()).close();
    }
}
