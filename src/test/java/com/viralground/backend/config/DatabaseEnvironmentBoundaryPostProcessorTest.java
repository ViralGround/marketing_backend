package com.viralground.backend.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.env.MockEnvironment;

import javax.sql.DataSource;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class DatabaseEnvironmentBoundaryPostProcessorTest {
    private static final AtomicInteger DATA_SOURCE_CREATIONS = new AtomicInteger();
    private static final String VERIFIED_TEST_RUNTIME_PROPERTY =
            "viralground.verified-test-runtime";

    @BeforeEach
    void resetCreationCount() {
        DATA_SOURCE_CREATIONS.set(0);
    }

    @Test
    void registeredPostProcessorRejectsRemoteDevelopmentTargetBeforeBeanCreation() {
        SpringApplication application = new SpringApplication(DataSourceProbe.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setBannerMode(Banner.Mode.OFF);
        application.setRegisterShutdownHook(false);

        assertThatThrownBy(() -> application.run(
                "--app.environment=development",
                "--spring.datasource.url=jdbc:postgresql://remote.example.test:5432/viralground",
                "--spring.main.log-startup-info=false"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("remote PostgreSQL");
        assertThat(DATA_SOURCE_CREATIONS).hasValue(0);
    }

    @Test
    void registeredPostProcessorRejectsEmbeddedDatabaseInProtectedEnvironment() {
        SpringApplication application = new SpringApplication(DataSourceProbe.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setBannerMode(Banner.Mode.OFF);
        application.setRegisterShutdownHook(false);

        assertThatThrownBy(() -> application.run(
                "--app.environment=preproduction",
                "--spring.datasource.url=jdbc:h2:mem:must-not-connect",
                "--spring.main.log-startup-info=false"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("embedded H2");
        assertThat(DATA_SOURCE_CREATIONS).hasValue(0);
    }

    @Test
    void protectedPreproductionRejectsLoopbackDespiteCompleteCloneLookingGuards() {
        SpringApplication application = new SpringApplication(DataSourceProbe.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setBannerMode(Banner.Mode.OFF);
        application.setRegisterShutdownHook(false);

        assertThatThrownBy(() -> application.run(
                "--app.environment=preproduction",
                "--spring.datasource.url=jdbc:postgresql://127.0.0.1:55432/viralground_local_staging?sslmode=verify-full",
                "--app.preproduction-database.clone-kind=sanitized",
                "--app.preproduction-database.allowed-hosts=127.0.0.1",
                "--app.preproduction-database.allowed-databases=viralground_local_staging",
                "--app.preproduction-database.production-host=prod-db.example.test",
                "--app.preproduction-database.production-database=viralground_production",
                "--app.preproduction-database.database-confirmation=I_ACKNOWLEDGE_THIS_IS_A_DISPOSABLE_CLONE",
                "--spring.main.log-startup-info=false"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("loopback PostgreSQL target");
        assertThat(DATA_SOURCE_CREATIONS).hasValue(0);
    }

    @Test
    void syntheticDevelopmentRuntimeMayUseLoopbackPostgres() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.environment", "development")
                .withProperty("spring.datasource.url",
                        "jdbc:postgresql://127.0.0.1:55432/viralground_local_staging");

        assertThatCode(() -> DatabaseEnvironmentBoundary.validate(environment))
                .doesNotThrowAnyException();
    }

    @Test
    void registeredPostProcessorRejectsDeclaredProductionBeforeBeanCreation() {
        SpringApplication application = new SpringApplication(DataSourceProbe.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setBannerMode(Banner.Mode.OFF);
        application.setRegisterShutdownHook(false);

        assertThatThrownBy(() -> application.run(
                "--app.environment=preproduction",
                "--spring.datasource.url=jdbc:postgresql://prod-db.example.test:5432/viralground_production?sslmode=verify-full",
                "--app.preproduction-database.clone-kind=sanitized",
                "--app.preproduction-database.allowed-hosts=prod-db.example.test",
                "--app.preproduction-database.allowed-databases=viralground_production",
                "--app.preproduction-database.production-host=prod-db.example.test",
                "--app.preproduction-database.production-database=viralground_production",
                "--app.preproduction-database.database-confirmation=I_ACKNOWLEDGE_THIS_IS_A_DISPOSABLE_CLONE",
                "--spring.main.log-startup-info=false"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("matches declared production");
        assertThat(DATA_SOURCE_CREATIONS).hasValue(0);
    }

    @Test
    void registeredPostProcessorRejectsUnlistedPreproductionCloneBeforeBeanCreation() {
        SpringApplication application = new SpringApplication(DataSourceProbe.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setBannerMode(Banner.Mode.OFF);
        application.setRegisterShutdownHook(false);

        assertThatThrownBy(() -> application.run(
                "--app.environment=preproduction",
                "--spring.datasource.url=jdbc:postgresql://wrong-clone.example.test:5432/viralground_rc_staging?sslmode=verify-full",
                "--app.preproduction-database.clone-kind=sanitized",
                "--app.preproduction-database.allowed-hosts=expected-clone.example.test",
                "--app.preproduction-database.allowed-databases=viralground_rc_staging",
                "--app.preproduction-database.production-host=prod-db.example.test",
                "--app.preproduction-database.production-database=viralground_production",
                "--app.preproduction-database.database-confirmation=I_ACKNOWLEDGE_THIS_IS_A_DISPOSABLE_CLONE",
                "--spring.main.log-startup-info=false"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outside its exact allowlist");
        assertThat(DATA_SOURCE_CREATIONS).hasValue(0);
    }

    @Test
    void packagedRuntimeCannotActivateTestProfile() {
        String prior = System.getProperty(VERIFIED_TEST_RUNTIME_PROPERTY);
        System.clearProperty(VERIFIED_TEST_RUNTIME_PROPERTY);
        try {
            SpringApplication application = new SpringApplication(DataSourceProbe.class);
            application.setWebApplicationType(WebApplicationType.NONE);
            application.setBannerMode(Banner.Mode.OFF);
            application.setRegisterShutdownHook(false);

            assertThatThrownBy(() -> application.run(
                    "--spring.profiles.active=test",
                    "--spring.datasource.url=jdbc:h2:mem:must-not-start",
                    "--spring.main.log-startup-info=false"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("verified Gradle test runtime");
            assertThat(DATA_SOURCE_CREATIONS).hasValue(0);
        } finally {
            if (prior == null) {
                System.clearProperty(VERIFIED_TEST_RUNTIME_PROPERTY);
            } else {
                System.setProperty(VERIFIED_TEST_RUNTIME_PROPERTY, prior);
            }
        }
    }

    @Test
    void effectiveEnvironmentCannotOverrideExplicitAppEnv() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("APP_ENV", "production")
                .withProperty("app.environment", "test")
                .withProperty("spring.datasource.url", "jdbc:h2:mem:must-not-connect");

        assertThatThrownBy(() -> DatabaseEnvironmentBoundary.validate(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must exactly match");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "jdbc:postgresql://db.example.test:5432/viralground",
            "jdbc:postgresql://db.example.test:5432/viralground?sslmode=require",
            "jdbc:postgresql://db.example.test:5432/viralground?sslmode=verify-full&sslmode=verify-full"
    })
    void protectedRemotePostgresRequiresExactlyOneVerifyFullTlsMode(String jdbcUrl) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.environment", "production")
                .withProperty("spring.datasource.url", jdbcUrl);

        assertThatThrownBy(() -> DatabaseEnvironmentBoundary.validate(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sslmode=verify-full");
    }

    @Test
    void protectedRemotePostgresAcceptsExactlyOneVerifyFullTlsMode() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.environment", "production")
                .withProperty("spring.datasource.url",
                        "jdbc:postgresql://db.example.test:5432/viralground"
                                + "?sslmode=verify-full&currentSchema=public");

        assertThatCode(() -> DatabaseEnvironmentBoundary.validate(environment))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "&ssl=true",
            "&sslfactory=org.postgresql.ssl.NonValidatingFactory",
            "&sslhostnameverifier=com.example.AcceptAllVerifier",
            "&sslfactoryarg=unsafe",
            "&sslnegotiation=direct"
    })
    void protectedPostgresRejectsAlternateTlsOverrideKeys(String suffix) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.environment", "production")
                .withProperty("spring.datasource.url",
                        "jdbc:postgresql://db.example.test:5432/viralground"
                                + "?sslmode=verify-full" + suffix);

        assertThatThrownBy(() -> DatabaseEnvironmentBoundary.validate(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TLS override");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https%3A%2F%2Fca.example.test%2Froot.pem",
            "replace-me",
            "relative%2Fca.pem",
            "%20"
    })
    void protectedPostgresRejectsUnsafeRootCertificateLocation(String rootCertificate) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.environment", "production")
                .withProperty("spring.datasource.url",
                        "jdbc:postgresql://db.example.test:5432/viralground"
                                + "?sslmode=verify-full&sslrootcert=" + rootCertificate);

        assertThatThrownBy(() -> DatabaseEnvironmentBoundary.validate(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sslrootcert");
    }

    @Test
    void protectedPostgresAllowsExplicitAbsoluteLocalRootCertificate() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.environment", "production")
                .withProperty("spring.datasource.url",
                        "jdbc:postgresql://db.example.test:5432/viralground"
                                + "?sslmode=verify-full"
                                + "&sslrootcert=%2Fetc%2Fssl%2Fcerts%2Fca-certificates.crt");

        assertThatCode(() -> DatabaseEnvironmentBoundary.validate(environment))
                .doesNotThrowAnyException();
    }

    @Configuration(proxyBeanMethods = false)
    static class DataSourceProbe {
        @Bean
        DataSource dataSource() {
            DATA_SOURCE_CREATIONS.incrementAndGet();
            return mock(DataSource.class);
        }
    }
}
