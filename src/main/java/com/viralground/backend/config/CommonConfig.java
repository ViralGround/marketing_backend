package com.viralground.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class CommonConfig {

    @Bean
    Clock systemClock() {
        // 서버/DB 리전과 무관하게 신규 도메인 로직은 UTC를 기준으로 기록한다.
        return Clock.systemUTC();
    }
}
