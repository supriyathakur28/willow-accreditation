package com.supriya.accreditation.command.api;

import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.validation.validator.constraints.ConstraintValidator;
import io.micronaut.validation.validator.constraints.ConstraintValidatorContext;
import jakarta.inject.Singleton;

/**
 * The length is checked first, so an oversized document is rejected before any work proportional to its size;
 * then one pass over the characters, without allocating the decoded bytes.
 */
@Singleton
public class Base64ContentValidator implements ConstraintValidator<Base64Content, String> {

    @Override
    public boolean isValid(
            final String value,
            final AnnotationValue<Base64Content> annotation,
            final ConstraintValidatorContext context) {
        if (value == null) {
            return true; // @NotBlank reports a missing value
        }
        final int maxDecodedBytes = annotation.intValue("maxDecodedBytes").orElse(Base64Content.DEFAULT_MAX_DECODED_BYTES);
        // Cheap bound first, so an oversized value costs nothing more
        if (value.length() > 4L * ((maxDecodedBytes + 2L) / 3)) {
            return false;
        }
        return isStandardBase64(value) && decodedLength(value) <= maxDecodedBytes;
    }

    /** Exact size after decoding a well-formed value. */
    static long decodedLength(final String value) {
        final int padding = value.endsWith("==") ? 2 : value.endsWith("=") ? 1 : 0;
        return value.length() / 4L * 3 - padding;
    }

    static boolean isStandardBase64(final String value) {
        final int length = value.length();
        if (length == 0 || length % 4 != 0) {
            return false;
        }
        final int padding = value.endsWith("==") ? 2 : value.endsWith("=") ? 1 : 0;
        for (int i = 0; i < length - padding; i++) {
            final char c = value.charAt(i);
            final boolean alphabet = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '+' || c == '/';
            if (!alphabet) {
                return false;
            }
        }
        return true;
    }
}
