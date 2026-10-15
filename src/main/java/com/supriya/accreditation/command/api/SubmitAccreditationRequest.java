package com.supriya.accreditation.command.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.supriya.accreditation.platform.web.PathIds;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The brief's POST body. Enum-like fields are strings validated here, so one 400 can list every invalid field
 * instead of failing on the first value the JSON parser cannot convert.
 */
@Serdeable
@JsonIgnoreProperties(ignoreUnknown = false)
public record SubmitAccreditationRequest(
        @NotBlank @Pattern(regexp = PathIds.USER_ID_PATTERN, message = "must be 1-64 characters: letters, digits, '_' or '-'")
        String userId,
        @NotNull @Pattern(regexp = "BY_INCOME|BY_NET_WORTH", message = "must be one of BY_INCOME, BY_NET_WORTH")
        String accreditationType,
        @NotNull @Valid
        Document document) {

    @Serdeable
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Document(
            @NotBlank @Size(max = 255, message = "must be at most 255 characters")
            String name,
            @NotNull @Pattern(regexp = "application/pdf|image/png|image/jpeg",
                    message = "must be one of application/pdf, image/png, image/jpeg")
            String mimeType,
            @NotBlank @Base64Content
            String content) {
    }
}
