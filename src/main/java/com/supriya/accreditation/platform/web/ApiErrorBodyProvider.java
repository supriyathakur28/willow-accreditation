package com.supriya.accreditation.platform.web;

import io.micronaut.context.annotation.Replaces;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.server.exceptions.response.ErrorContext;
import io.micronaut.http.server.exceptions.response.JsonErrorResponseBodyProvider;
import jakarta.inject.Singleton;

/**
 * Errors raised by the framework itself (unknown route, wrong method, body too large, malformed JSON)
 * use the same envelope as ours. Framework messages are not echoed: they can name internal types.
 */
@Singleton
@Replaces(JsonErrorResponseBodyProvider.class)
public class ApiErrorBodyProvider implements JsonErrorResponseBodyProvider<ApiError> {

    @Override
    public ApiError body(
            final ErrorContext context,
            final HttpResponse<?> response) {
        final String requestId = RequestIds.of(context.getRequest()).orElse(null);
        final HttpStatus status = response.getStatus();
        return switch (status) {
            case BAD_REQUEST -> ApiError.of(ErrorCodes.VALIDATION_FAILED,
                    "Request body is missing, is not valid JSON, or has fields of the wrong type or unknown fields",
                    requestId);
            case NOT_FOUND -> ApiError.of("NOT_FOUND", "No such resource", requestId);
            case METHOD_NOT_ALLOWED -> ApiError.of("METHOD_NOT_ALLOWED", "Method not allowed", requestId);
            case NOT_ACCEPTABLE -> ApiError.of("NOT_ACCEPTABLE", "Only application/json is produced", requestId);
            case UNSUPPORTED_MEDIA_TYPE -> ApiError.of("UNSUPPORTED_MEDIA_TYPE", "Send application/json", requestId);
            case REQUEST_ENTITY_TOO_LARGE -> ApiError.of("PAYLOAD_TOO_LARGE", "Request body is too large", requestId);
            case UNAUTHORIZED -> ApiError.of("UNAUTHORIZED", "A valid bearer token is required", requestId);
            case FORBIDDEN -> ApiError.of("FORBIDDEN", "Not allowed for this token", requestId);
            case SERVICE_UNAVAILABLE -> ApiError.of(ErrorCodes.SERVICE_UNAVAILABLE, "Please retry", requestId);
            default -> ApiError.of(status.getCode() >= 500 ? ErrorCodes.INTERNAL_ERROR : status.name(),
                    status.getReason(), requestId);
        };
    }
}
