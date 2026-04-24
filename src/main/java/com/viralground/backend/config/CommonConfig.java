package com.viralground.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class CommonConfig {

    @Bean
    Clock systemClock() {
        return Clock.systemDefaultZone();
    }
}
