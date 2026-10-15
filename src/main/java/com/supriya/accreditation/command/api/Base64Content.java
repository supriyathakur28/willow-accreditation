package com.supriya.accreditation.command.api;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Standard base64 whose decoded size is at most {@link #maxDecodedBytes()}; checked without decoding. */
@Documented
@Constraint(validatedBy = {}) // Micronaut finds the validator bean, Base64ContentValidator
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface Base64Content {

    String message() default "must be valid base64, max 5 MB decoded";

    int DEFAULT_MAX_DECODED_BYTES = 5 * 1024 * 1024;

    int maxDecodedBytes() default DEFAULT_MAX_DECODED_BYTES;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
