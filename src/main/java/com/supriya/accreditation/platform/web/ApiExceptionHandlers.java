package com.supriya.accreditation.platform.web;

import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import io.micronaut.validation.exceptions.ConstraintExceptionHandler;
import com.supriya.accreditation.platform.persistence.TransientErrors;
import jakarta.inject.Singleton;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.ElementKind;
import jakarta.validation.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** Maps exceptions to the {@link ApiError} envelope. Framework errors (404 route, 405, 413, bad JSON) go through {@link ApiErrorBodyProvider}. */
final class ApiExceptionHandlers {

    private static final String RETRY_AFTER_SECONDS = "1";

    private ApiExceptionHandlers() {
    }

    @Singleton
    static class ApiExceptionHandler implements ExceptionHandler<ApiException, HttpResponse<ApiError>> {
        @Override
        public HttpResponse<ApiError> handle(HttpRequest request, ApiException e) {
            return HttpResponse.<ApiError>status(e.status()).body(e.toBody(RequestIds.of(request).orElse(null)));
        }
    }

    @Singleton
    @Replaces(ConstraintExceptionHandler.class)
    @Requires(classes = ConstraintExceptionHandler.class)
    static class ConstraintViolationHandler implements ExceptionHandler<ConstraintViolationException, HttpResponse<ApiError>> {

        private static final Set<String> REQUIRED_CONSTRAINTS = Set.of("NotNull", "NotBlank", "NotEmpty");

        @Override
        public HttpResponse<ApiError> handle(HttpRequest request, ConstraintViolationException e) {
            List<ApiError.InvalidField> fields = new ArrayList<>();
            for (ConstraintViolation<?> violation : e.getConstraintViolations()) {
                fields.add(new ApiError.InvalidField(fieldName(violation.getPropertyPath()), errorFor(violation),
                        violation.getMessage()));
            }
            fields.sort(Comparator.comparing(ApiError.InvalidField::fieldName));
            return HttpResponse.badRequest(new ApiError(ErrorCodes.VALIDATION_FAILED, "Request is invalid",
                    RequestIds.of(request).orElse(null), fields, null));
        }

        private static String errorFor(ConstraintViolation<?> violation) {
            String constraint = violation.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName();
            if (REQUIRED_CONSTRAINTS.contains(constraint)) {
                return ErrorCodes.REQUIRED;
            }
            return "Base64Content".equals(constraint) ? ErrorCodes.INVALID_BASE64 : ErrorCodes.INVALID_VALUE;
        }

        // "submit.request.document.mimeType" -> "document.mime_type": the JSON name the caller sent
        private static String fieldName(Path path) {
            List<String> names = new ArrayList<>();
            for (Path.Node node : path) {
                if (node.getKind() == ElementKind.PROPERTY) {
                    names.add(snakeCase(node.getName()));
                }
            }
            if (names.isEmpty()) {
                // A violation on a method parameter itself, such as a header
                String last = null;
                for (Path.Node node : path) {
                    last = node.getName();
                }
                return last;
            }
            return String.join(".", names);
        }

        static String snakeCase(String camel) {
            return camel.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
        }
    }

    @Singleton
    static class ServiceUnavailableHandler implements ExceptionHandler<ServiceUnavailableException, HttpResponse<ApiError>> {

        private static final Logger log = LoggerFactory.getLogger(ServiceUnavailableHandler.class);

        @Override
        public HttpResponse<ApiError> handle(HttpRequest request, ServiceUnavailableException e) {
            log.warn("Answering 503: {}", e.getMessage(), e.getCause());
            return HttpResponse.<ApiError>status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header("Retry-After", RETRY_AFTER_SECONDS)
                    .body(ApiError.of(ErrorCodes.SERVICE_UNAVAILABLE, "Please retry", RequestIds.of(request).orElse(null)));
        }
    }

    /** Anything unexpected: logged with its stack trace, answered without internals. */
    @Singleton
    static class UnexpectedErrorHandler implements ExceptionHandler<RuntimeException, HttpResponse<ApiError>> {

        private static final Logger log = LoggerFactory.getLogger(UnexpectedErrorHandler.class);

        private final ServiceUnavailableHandler unavailable = new ServiceUnavailableHandler();

        @Override
        public HttpResponse<ApiError> handle(HttpRequest request, RuntimeException e) {
            if (TransientErrors.isTransient(e)) {
                return unavailable.handle(request, new ServiceUnavailableException("A dependency is unavailable", e));
            }
            log.error("Unexpected error on {} {}", request.getMethod(), request.getPath(), e);
            return HttpResponse.<ApiError>status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiError.of(
                    ErrorCodes.INTERNAL_ERROR, "Unexpected error", RequestIds.of(request).orElse(null)));
        }
    }
}
