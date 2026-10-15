package com.supriya.accreditation.platform.jobs;

import io.micronaut.aop.Around;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Runs the method on one instance at a time. The first instance to get the MySQL lock
 * {@code job:Class.method} runs; the others skip this run instead of queuing behind it.
 */
@Documented
@Around
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ScheduledJobLock {
}
