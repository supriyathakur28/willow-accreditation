package com.supriya.accreditation.platform.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.util.List;

/** The one error envelope every endpoint returns. Optional parts are omitted when empty. */
@Serdeable
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        String errorCode,
        String message,
        @Nullable String requestId,
        @Nullable List<InvalidField> invalidFields,
        @Nullable String currentStatus) {

    public static ApiError of(String errorCode, String message, @Nullable String requestId) {
        return new ApiError(errorCode, message, requestId, null, null);
    }

    @Serdeable
    public record InvalidField(String fieldName, String error, String message) {
    }
}
