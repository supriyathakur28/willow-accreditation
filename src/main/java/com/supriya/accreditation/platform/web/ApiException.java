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

    public ApiException(HttpStatus status, String errorCode, String message) {
        this(status, errorCode, message, null, null);
    }

    public ApiException(HttpStatus status, String errorCode, String message,
                        @Nullable List<ApiError.InvalidField> invalidFields, @Nullable String currentStatus) {
        super(message, null, false, false);
        this.status = status;
        this.errorCode = errorCode;
        this.invalidFields = invalidFields;
        this.currentStatus = currentStatus;
    }

    public static ApiException invalidField(String fieldName, String error, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_FAILED, "Request is invalid",
                List.of(new ApiError.InvalidField(fieldName, error, message)), null);
    }

    public HttpStatus status() {
        return status;
    }

    ApiError toBody(@Nullable String requestId) {
        return new ApiError(errorCode, getMessage(), requestId, invalidFields, currentStatus);
    }
}
