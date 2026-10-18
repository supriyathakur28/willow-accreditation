package com.supriya.accreditation.command.api;

import com.supriya.accreditation.command.AccreditationCommandService;
import com.supriya.accreditation.command.AccreditationCommandService.Submission;
import com.supriya.accreditation.command.FinaliseResult;
import com.supriya.accreditation.command.SubmitResult;
import com.supriya.accreditation.domain.AccreditationType;
import com.supriya.accreditation.domain.Outcome;
import com.supriya.accreditation.platform.Sha256;
import com.supriya.accreditation.platform.idempotency.IdempotencyGuard;
import com.supriya.accreditation.platform.security.Callers;
import com.supriya.accreditation.platform.security.Roles;
import com.supriya.accreditation.platform.web.ApiException;
import com.supriya.accreditation.platform.web.ConcurrencyLimit;
import com.supriya.accreditation.platform.web.ErrorCodes;
import com.supriya.accreditation.platform.web.PathIds;
import com.supriya.accreditation.platform.web.RequestIds;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Put;
import io.micronaut.json.JsonMapper;
import io.micronaut.openapi.annotation.OpenAPIGroup;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.Principal;
import java.util.OptionalLong;
import java.util.UUID;

/** Admin endpoints. Handlers block on JDBC and Redis, so they run on the blocking (virtual-thread) executor. */
@Controller("/user/accreditation")
@ExecuteOn(TaskExecutors.BLOCKING)
@Secured(Roles.ADMIN)
@ConcurrencyLimit("admin-writes")
@OpenAPIGroup("admin")
@Tag(
        name = "Accreditations",
        description = "Submit and decide accreditations. Requires an ADMIN token.")
@SecurityRequirement(name = "bearer")
public class AdminAccreditationController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String KEY_FORMAT = "[A-Za-z0-9_-]{1,64}";
    private static final java.util.regex.Pattern VERSION_TAG = java.util.regex.Pattern.compile("\"?(\\d{1,18})\"?");

    private final AccreditationCommandService commands;
    private final IdempotencyGuard idempotency;
    private final JsonMapper json;

    AdminAccreditationController(
            final AccreditationCommandService commands,
            final IdempotencyGuard idempotency,
            final JsonMapper json) {
        this.commands = commands;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Post
    public HttpResponse<AccreditationIdResponse> submit(
            @Body @Valid final SubmitAccreditationRequest request,
            @Header(IDEMPOTENCY_KEY) @Nullable @Pattern(regexp = KEY_FORMAT, message = PathIds.ID_FORMAT_MESSAGE) final String idempotencyKey,
            @Nullable final Principal principal) {
        final String actor = Callers.actor(principal);
        final String requestSha256 = Sha256.hex(canonicalJson(request));
        if (idempotencyKey != null) {
            idempotency.check(actor, "submit", idempotencyKey, requestSha256);
        }
        final SubmitResult result = commands.submit(new Submission(request.userId(),
                AccreditationType.valueOf(request.accreditationType()), request.document().name(),
                request.document().mimeType(), request.document().content(), actor, idempotencyKey, requestSha256,
                RequestIds.current().orElse(null)));
        return switch (result) {
            case SubmitResult.Created created -> HttpResponse.created(new AccreditationIdResponse(created.accreditationId()));
            case SubmitResult.UserNotFound ignored -> throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "USER_NOT_FOUND", "User " + request.userId() + " is not registered");
            case SubmitResult.PendingExists ignored -> throw new ApiException(HttpStatus.CONFLICT,
                    "PENDING_ACCREDITATION_EXISTS", "User " + request.userId() + " already has a PENDING accreditation");
            case SubmitResult.KeyReused ignored -> throw IdempotencyGuard.keyReused();
        };
    }

    @Put("/{accreditationId}")
    public HttpResponse<AccreditationIdResponse> finalise(
            @PathVariable final String accreditationId,
            @Body @Valid final FinaliseAccreditationRequest request,
            @Header(HttpHeaders.IF_MATCH) @Nullable final String ifMatch,
            @Header(IDEMPOTENCY_KEY) @Nullable @Pattern(regexp = KEY_FORMAT, message = PathIds.ID_FORMAT_MESSAGE) final String idempotencyKey,
            @Nullable final Principal principal) {
        final UUID id = PathIds.accreditationId(accreditationId);
        final OptionalLong expectedVersion = parseIfMatch(ifMatch);
        final String actor = Callers.actor(principal);
        if (idempotencyKey != null) {
            idempotency.check(actor, "finalise:" + id, idempotencyKey, Sha256.hex(canonicalJson(request)));
        }
        final FinaliseResult result = commands.finalise(id, Outcome.valueOf(request.outcome()), expectedVersion, actor,
                RequestIds.current().orElse(null));
        return switch (result) {
            case FinaliseResult.Applied applied -> HttpResponse.ok(new AccreditationIdResponse(id))
                    .header(HttpHeaders.ETAG, "\"" + applied.accreditation().version() + "\"");
            case FinaliseResult.NotFound ignored -> throw PathIds.accreditationNotFound(accreditationId);
            case FinaliseResult.Rejected rejected -> throw switch (rejected.reason()) {
                case TRANSITION_NOT_ALLOWED -> new ApiException(HttpStatus.CONFLICT, "TRANSITION_NOT_ALLOWED",
                        "Cannot change status from " + rejected.current().status() + " to " + request.outcome(),
                        null, rejected.current().status().name());
                case VERSION_MISMATCH -> new ApiException(HttpStatus.PRECONDITION_FAILED, "VERSION_MISMATCH",
                        "If-Match expected version " + expectedVersion.getAsLong() + " but the accreditation is at version "
                                + rejected.current().version(), null, rejected.current().status().name());
                case NOT_ELIGIBLE_FOR_EXPIRY -> new IllegalStateException("Expiry rejection from an admin command");
            };
        };
    }

    private static OptionalLong parseIfMatch(@Nullable final String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank() || ifMatch.strip().equals("*")) {
            return OptionalLong.empty();
        }
        final var matcher = VERSION_TAG.matcher(ifMatch.strip());
        if (!matcher.matches()) {
            throw ApiException.invalidField("If-Match", ErrorCodes.INVALID_VALUE,
                    "must be the version from the ETag, for example \"2\"");
        }
        return OptionalLong.of(Long.parseLong(matcher.group(1)));
    }

    // Re-serialising the parsed body ignores whitespace and field order, so equivalent retries hash the same
    private byte[] canonicalJson(final Object request) {
        try {
            return json.writeValueAsBytes(request);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
