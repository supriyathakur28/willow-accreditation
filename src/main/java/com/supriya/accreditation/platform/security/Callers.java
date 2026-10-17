package com.supriya.accreditation.platform.security;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpStatus;
import io.micronaut.security.authentication.Authentication;
import com.supriya.accreditation.platform.web.ApiException;

import java.security.Principal;

/** Who is calling. With security off there is no principal and every call is "anonymous". */
public final class Callers {

    public static final String ANONYMOUS = "anonymous";

    private Callers() {
    }

    /** The audit actor: only ever the authenticated subject, never a value from the body or a header. */
    public static String actor(@Nullable final Principal principal) {
        return principal == null ? ANONYMOUS : principal.getName();
    }

    /**
     * An admin may read any user's accreditations; a user token only its own. Takes a {@link Principal} because
     * Micronaut can bind one as null when security is off, but cannot bind an {@link Authentication} parameter then.
     */
    public static void requireMayRead(
            @Nullable final Principal principal,
            final String userId) {
        if (!(principal instanceof Authentication authentication) || authentication.getRoles().contains(Roles.ADMIN)) {
            return;
        }
        final Object tokenUserId = authentication.getAttributes().get(Roles.USER_ID_CLAIM);
        if (!userId.equals(tokenUserId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "This token may not read another user's accreditations");
        }
    }
}
