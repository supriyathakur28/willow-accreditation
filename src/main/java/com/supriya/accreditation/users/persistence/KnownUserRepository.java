package com.supriya.accreditation.users.persistence;

import io.micronaut.data.annotation.Query;
import io.micronaut.data.annotation.Repository;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.GenericRepository;

import java.time.Instant;
import java.util.Optional;

/** The local copy of the users the Users service knows, in the write model, where POST checks it. */
@Repository("write")
@JdbcRepository(dialect = Dialect.MYSQL)
public interface KnownUserRepository extends GenericRepository<KnownUserRow, String> {

    /** Redelivered user events are a no-op: the first registration is kept. */
    @Query("""
            INSERT INTO known_users (user_id, registered_at) VALUES (:userId, :registeredAt)
            ON DUPLICATE KEY UPDATE user_id = user_id""")
    void insertIfAbsent(
            String userId,
            Instant registeredAt);

    boolean existsById(String userId);

    Optional<KnownUserRow> findById(String userId);

    /**
     * {@code FOR SHARE} holds the user row until the submit commits, so the check cannot go stale mid-transaction.
     * Must run inside a write transaction.
     */
    @Query("SELECT COUNT(*) FROM known_users WHERE user_id = :userId FOR SHARE")
    long countByIdForShare(String userId);

    default boolean existsForShare(String userId) {
        return countByIdForShare(userId) > 0;
    }
}
