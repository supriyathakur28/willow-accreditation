package com.supriya.accreditation.platform.web;

import io.micronaut.aop.InterceptorBean;
import io.micronaut.aop.MethodInterceptor;
import io.micronaut.aop.MethodInvocationContext;
import io.micronaut.context.env.Environment;
import jakarta.inject.Singleton;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

@Singleton
@InterceptorBean(ConcurrencyLimit.class)
public class ConcurrencyLimitInterceptor implements MethodInterceptor<Object, Object> {

    private static final int DEFAULT_LIMIT = 100;

    private final Environment environment;
    private final Map<String, Semaphore> limits = new ConcurrentHashMap<>();

    ConcurrencyLimitInterceptor(final Environment environment) {
        this.environment = environment;
    }

    @Override
    public Object intercept(final MethodInvocationContext<Object, Object> context) {
        final String group = context.stringValue(ConcurrencyLimit.class).orElseThrow();
        final Semaphore permits = limits.computeIfAbsent(group, name -> new Semaphore(
                environment.getProperty("concurrency-limits." + name, Integer.class, DEFAULT_LIMIT)));
        if (!permits.tryAcquire()) {
            throw new ServiceUnavailableException("Too many concurrent requests for " + group, null);
        }
        try {
            return context.proceed();
        } finally {
            permits.release();
        }
    }
}
