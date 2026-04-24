package com.viralground.backend.dto.metric;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpsertMetricRequest {

    @NotNull
    @Min(0)
    private Long views;

    @NotNull
    @Min(0)
    private Long likes;

    @NotNull
    @Min(0)
    private Long comments;

    private String externalUrl;
}
