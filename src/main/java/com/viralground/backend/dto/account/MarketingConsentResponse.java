package com.viralground.backend.dto.account;

import java.time.LocalDateTime;

public record MarketingConsentResponse(boolean optedIn, LocalDateTime optedInAt) {}
