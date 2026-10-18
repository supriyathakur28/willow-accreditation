package com.supriya.accreditation.platform.security;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.supriya.accreditation.platform.web.ApiException;
import com.supriya.accreditation.platform.web.ErrorCodes;
import com.supriya.accreditation.platform.web.PathIds;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Post;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.security.token.jwt.generator.JwtTokenGenerator;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.List;
import java.util.Map;

/**
 * Local stand-in for the authentication service. In production admins and users log in there (email and password,
 * out of scope here) and receive these same tokens; this endpoint skips the login so the API can be tried locally.
 * It exists only when security is enabled.
 */
@Controller("/auth/token")
@Hidden // local only: never part of a published contract
@Secured(SecurityRule.IS_ANONYMOUS)
@Requires(property = "micronaut.security.enabled", value = "true")
public class TokenController {

    private final JwtTokenGenerator tokens;
    private final int expirySeconds;

    TokenController(
            final JwtTokenGenerator tokens,
            @Value("${micronaut.security.token.generator.access-token.expiration:3600}") final int expirySeconds) {
        this.tokens = tokens;
        this.expirySeconds = expirySeconds;
    }

    @Serdeable
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record TokenRequest(
            @NotNull @Pattern(regexp = "ADMIN|USER", message = "must be one of ADMIN, USER") String role,
            @Nullable @Pattern(regexp = "[A-Za-z0-9_.:-]{1,64}",
                    message = "must be 1-64 characters: letters, digits, '_', '.', ':' or '-'") String subject,
            @Nullable @Pattern(regexp = PathIds.USER_ID_PATTERN, message = PathIds.ID_FORMAT_MESSAGE) String userId) {
    }

    @Serdeable
    public record TokenResponse(
            String accessToken,
            String tokenType,
            int expiresIn) {
    }

    @Post
    public TokenResponse issue(@Body @Valid final TokenRequest request) {
        final Authentication authentication = Roles.ADMIN.equals(request.role())
                ? Authentication.build(request.subject() == null ? "admin" : request.subject(), List.of(Roles.ADMIN))
                : Authentication.build("user:" + required(request.userId()), List.of(Roles.USER),
                Map.of(Roles.USER_ID_CLAIM, request.userId()));
        final String token = tokens.generateToken(authentication, expirySeconds)
                .orElseThrow(() -> new IllegalStateException("Token generation is not configured"));
        return new TokenResponse(token, "Bearer", expirySeconds);
    }

    private static String required(@Nullable final String userId) {
        if (userId == null) {
            throw ApiException.invalidField("user_id", ErrorCodes.REQUIRED, "is required for a USER token");
        }
        return userId;
    }
}
