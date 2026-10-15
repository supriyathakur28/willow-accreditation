package com.supriya.accreditation.users.persistence;

import io.micronaut.data.annotation.Query;
import io.micronaut.data.annotation.Repository;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;

@Repository("write")
@JdbcRepository(dialect = Dialect.MYSQL)
public interface WriteKnownUserRepository extends KnownUserOperations {

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
