package com.viralground.backend.dto.admin;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateCampaignFeaturedRequest {

    @NotNull
    private Boolean featured;
}
