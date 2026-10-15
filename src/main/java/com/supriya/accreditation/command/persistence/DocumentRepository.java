package com.supriya.accreditation.command.persistence;

import io.micronaut.data.annotation.Repository;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.GenericRepository;

import java.util.Optional;
import java.util.UUID;

@Repository("write")
@JdbcRepository(dialect = Dialect.MYSQL)
public interface DocumentRepository extends GenericRepository<DocumentRow, UUID> {

    DocumentRow save(DocumentRow row);

    Optional<DocumentRow> findById(UUID accreditationId);
}
