package com.viralground.backend.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Runs before the application context can instantiate a DataSource, Flyway,
 * Hibernate, or any bootstrap runner. This is the earliest database target
 * boundary; bean-level validation remains a second line of defence.
 */
public final class DatabaseEnvironmentBoundaryPostProcessor
        implements EnvironmentPostProcessor, Ordered {

    @Override
    public void postProcessEnvironment(
            ConfigurableEnvironment environment, SpringApplication application) {
        DatabaseEnvironmentBoundary.validate(environment);
    }

    @Override
    public int getOrder() {
        // Config data and OS environment values must already be available.
        return Ordered.LOWEST_PRECEDENCE;
    }
}
