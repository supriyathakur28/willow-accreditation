package com.supriya.accreditation.command.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

@Serdeable
@JsonIgnoreProperties(ignoreUnknown = false)
public record FinaliseAccreditationRequest(
        @NotNull @Pattern(regexp = "CONFIRMED|EXPIRED|FAILED", message = "must be one of CONFIRMED, EXPIRED, FAILED")
        String outcome) {
}
