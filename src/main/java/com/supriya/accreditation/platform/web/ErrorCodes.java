package com.supriya.accreditation.platform.web;

/** Error codes shared by every endpoint. Business-specific codes live with their controllers. */
public final class ErrorCodes {

    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    public static final String IDEMPOTENCY_KEY_REUSED = "IDEMPOTENCY_KEY_REUSED";
    public static final String SERVICE_UNAVAILABLE = "SERVICE_UNAVAILABLE";
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    // invalid_fields[].error
    public static final String REQUIRED = "REQUIRED";
    public static final String INVALID_VALUE = "INVALID_VALUE";
    public static final String INVALID_BASE64 = "INVALID_BASE64";

    private ErrorCodes() {
    }
}
