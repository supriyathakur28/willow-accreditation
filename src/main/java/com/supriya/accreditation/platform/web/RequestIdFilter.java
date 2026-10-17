package com.supriya.accreditation.platform.web;

import io.micronaut.context.propagation.slf4j.MdcPropagationContext;
import io.micronaut.core.order.Ordered;
import io.micronaut.core.propagation.MutablePropagatedContext;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ResponseFilter;
import io.micronaut.http.annotation.ServerFilter;
import io.micronaut.http.filter.ServerFilterPhase;

import java.util.Map;

/**
 * Gives every request an ID, puts it in the logging context (which follows the request onto the blocking
 * executor) and echoes it on every response, errors included.
 */
@ServerFilter(ServerFilter.MATCH_ALL_PATTERN)
public class RequestIdFilter implements Ordered {

    @RequestFilter
    public void assign(
            final HttpRequest<?> request,
            final MutablePropagatedContext context) {
        final String requestId = RequestIds.acceptOrGenerate(request.getHeaders().get(RequestIds.HEADER));
        request.setAttribute(RequestIds.ATTRIBUTE, requestId);
        context.add(new MdcPropagationContext(Map.of(RequestIds.MDC_KEY, requestId)));
    }

    @ResponseFilter
    public void echo(
            final HttpRequest<?> request,
            final MutableHttpResponse<?> response) {
        RequestIds.of(request).ifPresent(id -> response.header(RequestIds.HEADER, id));
    }

    @Override
    public int getOrder() {
        // Before security, so a 401 also carries the ID
        return ServerFilterPhase.FIRST.order();
    }
}
