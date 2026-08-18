package com.viralground.backend.repository;

import com.viralground.backend.entity.MarketingConsentEvent;
import org.springframework.data.repository.Repository;

public interface MarketingConsentEventRepository extends Repository<MarketingConsentEvent, Long> {
    <S extends MarketingConsentEvent> S saveAndFlush(S event);
}
