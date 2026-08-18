package com.viralground.backend.dto.account;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record MarketingConsentRequest(
        @NotNull Boolean optedIn,
        @Size(max = 80) String marketingVersion
) {}
