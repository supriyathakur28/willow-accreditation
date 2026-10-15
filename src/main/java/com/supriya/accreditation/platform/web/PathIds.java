package com.supriya.accreditation.platform.web;

import io.micronaut.http.HttpStatus;

import java.util.UUID;
import java.util.regex.Pattern;

/** Identifiers taken from URL paths. */
public final class PathIds {

    public static final String USER_ID_PATTERN = "[A-Za-z0-9_-]{1,64}";

    private static final Pattern CANONICAL_UUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern USER_ID = Pattern.compile(USER_ID_PATTERN);

    private PathIds() {
    }

    /**
     * Accepts either case (the brief's example ID has an upper-case letter). A malformed ID cannot exist, so it is a
     * 404, not a 400. Only the canonical 8-4-4-4-12 form is accepted: {@link UUID#fromString} alone also accepts
     * forms such as "1-2-3-4-5".
     */
    public static UUID accreditationId(String value) {
        if (!CANONICAL_UUID.matcher(value).matches()) {
            throw accreditationNotFound(value);
        }
        return UUID.fromString(value);
    }

    public static String userId(String value) {
        if (!USER_ID.matcher(value).matches()) {
            throw ApiException.invalidField("user_id", ErrorCodes.INVALID_VALUE,
                    "must be 1-64 characters: letters, digits, '_' or '-'");
        }
        return value;
    }

    public static ApiException accreditationNotFound(String value) {
        return new ApiException(HttpStatus.NOT_FOUND, "ACCREDITATION_NOT_FOUND", "Accreditation " + value + " not found");
    }
}
