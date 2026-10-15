package com.supriya.accreditation.users.persistence;

import io.micronaut.data.annotation.Repository;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;

@Repository("read")
@JdbcRepository(dialect = Dialect.MYSQL)
public interface ReadKnownUserRepository extends KnownUserOperations {
}
