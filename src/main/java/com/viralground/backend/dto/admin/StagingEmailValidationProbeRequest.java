package com.viralground.backend.dto.admin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;
import com.viralground.backend.notification.StagingEmailValidationTemplate;
import jakarta.validation.constraints.NotNull;

/** No recipient, free-form content, identifier, or token is accepted. */
@JsonIgnoreProperties(ignoreUnknown = false)
public record StagingEmailValidationProbeRequest(
        @NotNull StagingEmailValidationTemplate template) {

    @JsonAnySetter
    public void rejectUnknownField(String ignoredName, JsonNode ignoredValue) {
        throw new IllegalArgumentException(
                "unsupported staging email validation probe field");
    }
}
