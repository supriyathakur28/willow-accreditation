package com.supriya.accreditation.platform.web;

import io.micronaut.aop.Around;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Caps in-flight calls per named group ({@code concurrency-limits.<name>}), so overload is shed at once with 503
 * instead of queuing behind the connection pool. Admin and client routes use separate groups (bulkheads).
 */
@Documented
@Around
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface ConcurrencyLimit {

    String value();
}
