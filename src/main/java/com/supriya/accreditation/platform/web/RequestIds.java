package com.supriya.accreditation.platform.web;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.context.ServerRequestContext;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** The request ID: accepted from the caller only if it is safe to put in logs and headers, otherwise generated. */
public final class RequestIds {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";
    static final String ATTRIBUTE = "accreditation.requestId";

    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9-]{1,64}");

    private RequestIds() {
    }

    static String acceptOrGenerate(String supplied) {
        return supplied != null && SAFE.matcher(supplied).matches() ? supplied : UUID.randomUUID().toString();
    }

    public static Optional<String> of(HttpRequest<?> request) {
        return request.getAttribute(ATTRIBUTE, String.class);
    }

    /** The ID of the request being handled on this thread, if any. */
    public static Optional<String> current() {
        return ServerRequestContext.currentRequest().flatMap(RequestIds::of);
    }
}
