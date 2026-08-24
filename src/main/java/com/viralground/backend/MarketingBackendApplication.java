package com.viralground.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
@ConfigurationPropertiesScan
public class MarketingBackendApplication {

	public static void main(String[] args) {
		ConfigurableApplicationContext context =
				SpringApplication.run(MarketingBackendApplication.class, args);
		closeCompletedMigrationRunner(context);
	}

	/**
	 * A guarded clone migration uses the complete production startup chain so Flyway and
	 * Hibernate validation cannot drift from the application. Explicitly close only that
	 * one-shot context after successful startup; never depend on scheduler/thread behavior
	 * or a timeout to infer success.
	 */
	static void closeCompletedMigrationRunner(ConfigurableApplicationContext context) {
		boolean migrationRunner = context.getEnvironment().getProperty(
				"app.migration-runner.enabled", Boolean.class, false);
		if (migrationRunner) {
			context.close();
		}
	}

}
