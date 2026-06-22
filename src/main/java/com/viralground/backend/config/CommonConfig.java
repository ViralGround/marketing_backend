package com.viralground.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

@Configuration
@EnableScheduling
public class CommonConfig {

    @Bean
    Clock systemClock() {
        return Clock.systemDefaultZone();
    }
}
