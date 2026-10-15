package com.supriya.accreditation.platform.security;

public final class Roles {

    public static final String ADMIN = "ADMIN";
    public static final String USER = "USER";
    /** Claim carried by user tokens: the only user whose accreditations the token may read. */
    public static final String USER_ID_CLAIM = "user_id";

    private Roles() {
    }
}
