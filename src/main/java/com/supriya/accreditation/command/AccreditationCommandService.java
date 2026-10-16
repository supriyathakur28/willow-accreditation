package com.supriya.accreditation.command;

import com.supriya.accreditation.command.persistence.AccreditationRepository;
import com.supriya.accreditation.command.persistence.AccreditationRow;
import com.supriya.accreditation.command.persistence.DocumentRepository;
import com.supriya.accreditation.command.persistence.DocumentRow;
import com.supriya.accreditation.domain.Accreditation;
import com.supriya.accreditation.domain.AccreditationCommand.ExpireIfStale;
import com.supriya.accreditation.domain.AccreditationCommand.Finalise;
import com.supriya.accreditation.domain.AccreditationDecider;
import com.supriya.accreditation.domain.AccreditationEvent.AccreditationSubmitted;
import com.supriya.accreditation.domain.AccreditationType;
import com.supriya.accreditation.domain.Decision;
import com.supriya.accreditation.domain.Outcome;
import com.supriya.accreditation.platform.UuidV7Generator;
import com.supriya.accreditation.platform.persistence.DuplicateKeys;
import com.supriya.accreditation.platform.persistence.WriteTransactions;
import com.supriya.accreditation.users.persistence.KnownUserRepository;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * The imperative shell around {@link AccreditationDecider}: lock, decide, persist the state and its event in one
 * transaction. No remote calls happen inside a transaction.
 */
@Singleton
public class AccreditationCommandService {

    private static final Logger log = LoggerFactory.getLogger(AccreditationCommandService.class);

    private final WriteTransactions transactions;
    private final AccreditationRepository accreditations;
    private final DocumentRepository documents;
    private final KnownUserRepository knownUsers;
    private final OutboxWriter outbox;
    private final UuidV7Generator ids;
    private final Clock clock;

    AccreditationCommandService(WriteTransactions transactions, AccreditationRepository accreditations,
                                DocumentRepository documents, KnownUserRepository knownUsers,
                                OutboxWriter outbox, UuidV7Generator ids, Clock clock) {
        this.transactions = transactions;
        this.accreditations = accreditations;
        this.documents = documents;
        this.knownUsers = knownUsers;
        this.outbox = outbox;
        this.ids = ids;
        this.clock = clock;
    }

    public record Submission(String userId, AccreditationType type, String documentName, String mimeType,
                             String base64Content, String actor, @Nullable String idempotencyKey,
                             String requestSha256, @Nullable String requestId) {
    }

    public SubmitResult submit(Submission submission) {
        DecodedDocument document = DecodedDocument.decode(
                submission.documentName(), submission.mimeType(), submission.base64Content());
        Instant now = clock.instant();
        AccreditationSubmitted event = AccreditationDecider.submit(ids.next(), submission.userId(), submission.type(),
                document.sha256(), submission.actor(), now, ids.next());
        try {
            return transactions.inTransaction(() -> insert(submission, document, event));
        } catch (RuntimeException e) {
            if (DuplicateKeys.violatedKey(e).isEmpty()) {
                throw e;
            }
            // A retry can hit the idempotency key and the one-PENDING rule at once, and MySQL reports only one of
            // them, so every duplicate is resolved the same way: look the key up in a fresh transaction.
            return resolveDuplicate(submission);
        }
    }

    private SubmitResult insert(Submission submission, DecodedDocument document, AccreditationSubmitted event) {
        // FOR SHARE holds the user row until commit, so the check cannot go stale before the insert
        if (!knownUsers.existsForShare(submission.userId())) {
            return new SubmitResult.UserNotFound();
        }
        accreditations.save(new AccreditationRow(event.accreditationId(), event.userId(), event.accreditationType(),
                event.status(), event.version(), submission.actor(), submission.idempotencyKey(),
                submission.idempotencyKey() == null ? null : submission.requestSha256(),
                event.occurredAt(), event.occurredAt(), submission.actor()));
        documents.save(new DocumentRow(event.accreditationId(), document.fileName(), document.mimeType(),
                document.content().length, document.sha256(), document.content(), event.occurredAt()));
        outbox.append(event, submission.requestId());
        log.info("Accreditation {} submitted for user {}", event.accreditationId(), event.userId());
        return new SubmitResult.Created(event.accreditationId());
    }

    private SubmitResult resolveDuplicate(Submission submission) {
        if (submission.idempotencyKey() == null) {
            return new SubmitResult.PendingExists();
        }
        Optional<AccreditationRow> original = transactions.inTransaction(() ->
                accreditations.findByCreatedByAndIdempotencyKey(submission.actor(), submission.idempotencyKey()));
        if (original.isEmpty()) {
            return new SubmitResult.PendingExists();
        }
        if (!submission.requestSha256().equals(original.get().requestSha256())) {
            return new SubmitResult.KeyReused();
        }
        return new SubmitResult.Created(original.get().id());
    }

    public FinaliseResult finalise(UUID accreditationId, Outcome outcome, OptionalLong expectedVersion,
                                   String actor, @Nullable String requestId) {
        return transactions.inTransaction(() -> {
            // Lock, then read: the 404, the If-Match check and the decision all use this locked row
            Optional<AccreditationRow> locked = accreditations.findByIdForUpdate(accreditationId);
            if (locked.isEmpty()) {
                return new FinaliseResult.NotFound();
            }
            AccreditationRow row = locked.get();
            Accreditation current = row.toDomain();
            Decision decision = AccreditationDecider.decide(
                    current, new Finalise(outcome, actor, expectedVersion), clock.instant(), ids.next());
            return switch (decision) {
                case Decision.Accepted accepted -> {
                    Accreditation next = AccreditationDecider.evolve(current, accepted.event());
                    accreditations.update(row.withState(next));
                    outbox.append(accepted.event(), requestId);
                    log.info("Accreditation {} moved {} -> {} (version {})",
                            accreditationId, current.status(), next.status(), next.version());
                    yield new FinaliseResult.Applied(next);
                }
                case Decision.NoOp noOp -> new FinaliseResult.Applied(noOp.current());
                case Decision.Rejected rejected -> new FinaliseResult.Rejected(rejected.reason(), rejected.current());
            };
        });
    }

    public enum ExpiryOutcome { EXPIRED, SKIPPED }

    /**
     * Expires one accreditation if it is still eligible on the locked row. The job found it with a plain read, so an
     * admin may have changed it since; the re-check below means nothing is decided on the scanned values.
     */
    public ExpiryOutcome expireIfStale(UUID accreditationId, Instant cutoff) {
        return transactions.inTransaction(() -> {
            Optional<AccreditationRow> locked = accreditations.findByIdForUpdate(accreditationId);
            if (locked.isEmpty()) {
                return ExpiryOutcome.SKIPPED;
            }
            AccreditationRow row = locked.get();
            Accreditation current = row.toDomain();
            Decision decision = AccreditationDecider.decide(current,
                    new ExpireIfStale(cutoff, AccreditationDecider.EXPIRY_JOB_ACTOR), clock.instant(), ids.next());
            if (!(decision instanceof Decision.Accepted accepted)) {
                return ExpiryOutcome.SKIPPED;
            }
            Accreditation next = AccreditationDecider.evolve(current, accepted.event());
            accreditations.update(row.withState(next));
            outbox.append(accepted.event(), null);
            log.info("Accreditation {} expired (confirmed at {}, version {})", accreditationId, current.updatedAt(),
                    next.version());
            return ExpiryOutcome.EXPIRED;
        });
    }
}
