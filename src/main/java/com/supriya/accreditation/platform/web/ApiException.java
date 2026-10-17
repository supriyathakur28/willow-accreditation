package com.supriya.accreditation.platform.web;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpStatus;

import java.util.List;

/**
 * An HTTP-level failure with a stable error code. Business outcomes are values returned by the services;
 * controllers turn them into this at the edge.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;
    private final List<ApiError.InvalidField> invalidFields;
    private final String currentStatus;

    public ApiException(
            final HttpStatus status,
            final String errorCode,
            final String message) {
        this(status, errorCode, message, null, null);
    }

    public ApiException(
            final HttpStatus status,
            final String errorCode,
            final String message,
            @Nullable final List<ApiError.InvalidField> invalidFields,
            @Nullable final String currentStatus) {
        super(message, null, false, false);
        this.status = status;
        this.errorCode = errorCode;
        this.invalidFields = invalidFields;
        this.currentStatus = currentStatus;
    }

    public static ApiException invalidField(
            final String fieldName,
            final String error,
            final String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_FAILED, "Request is invalid",
                List.of(new ApiError.InvalidField(fieldName, error, message)), null);
    }

    public HttpStatus status() {
        return status;
    }

    ApiError toBody(@Nullable final String requestId) {
        return new ApiError(errorCode, getMessage(), requestId, invalidFields, currentStatus);
    }
}
