-- Admin write model: every business check and every state change happens here.
-- IDs are UUIDv7 in their canonical text form (lower-case, 36 characters), readable as they appear in the API.
-- UUIDv7 text sorts in creation order, so inserts still append to the end of each index.
-- user_id is an opaque, case-sensitive token, so it uses a binary collation everywhere:
-- the default (case-insensitive) collation would treat 'abcD' and 'ABCd' as one user.

CREATE TABLE accreditations (
    id                  CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,           -- UUIDv7, time-ordered: inserts append to the index
    user_id             VARCHAR(64)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    accreditation_type  VARCHAR(20)  NOT NULL,
    status              VARCHAR(20)  NOT NULL,
    version             BIGINT       NOT NULL,                       -- 1 on submit, +1 on every change; copied into every event
    created_by          VARCHAR(128) NOT NULL,
    idempotency_key     VARCHAR(64)  CHARACTER SET ascii COLLATE ascii_bin NULL,
    request_sha256      CHAR(64)     CHARACTER SET ascii NULL,
    created_at          DATETIME(6)  NOT NULL,                       -- UTC
    updated_at          DATETIME(6)  NOT NULL,                       -- UTC; equals created_at on insert
    updated_by          VARCHAR(128) NOT NULL,
    -- MySQL has no partial unique index, so rule 4c (one PENDING per user) uses a generated column:
    -- user_id while PENDING, NULL otherwise. NULLs never collide in a unique index.
    user_id_if_pending  VARCHAR(64)  CHARACTER SET ascii COLLATE ascii_bin
        GENERATED ALWAYS AS (CASE WHEN status = 'PENDING' THEN user_id END) STORED,

    PRIMARY KEY (id),
    CONSTRAINT chk_accreditations_type   CHECK (accreditation_type IN ('BY_INCOME', 'BY_NET_WORTH')),
    CONSTRAINT chk_accreditations_status CHECK (status IN ('PENDING', 'CONFIRMED', 'EXPIRED', 'FAILED')),
    -- Rule 4c, enforced by the database whichever code path writes
    UNIQUE KEY uq_accreditations_one_pending_per_user (user_id_if_pending),
    -- Duplicate POSTs with the same Idempotency-Key, scoped per caller; NULL keys never collide
    UNIQUE KEY uq_accreditations_idempotency_key (created_by, idempotency_key),
    -- Expiry job: range scan of status = 'CONFIRMED' AND updated_at < cutoff
    KEY ix_accreditations_status_updated_at (status, updated_at),
    KEY ix_accreditations_user_id (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- The evidence document, kept apart so status reads never touch the blob
CREATE TABLE accreditation_documents (
    accreditation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    file_name        VARCHAR(255) NOT NULL,
    mime_type        VARCHAR(100) NOT NULL,
    size_bytes       INT          NOT NULL,
    sha256           CHAR(64)     CHARACTER SET ascii NOT NULL,
    content          MEDIUMBLOB   NOT NULL,                          -- up to 16 MB; the API caps documents at 5 MB
    created_at       DATETIME(6)  NOT NULL,

    PRIMARY KEY (accreditation_id),
    CONSTRAINT fk_accreditation_documents_accreditation
        FOREIGN KEY (accreditation_id) REFERENCES accreditations (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Transactional outbox: written in the same transaction as the state change, published by the relay
CREATE TABLE outbox_events (
    id           BIGINT       NOT NULL AUTO_INCREMENT,              -- publish order; never exposed
    event_id     CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    aggregate_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,                             -- accreditation_id, the Kafka key
    event_type   VARCHAR(64)  NOT NULL,
    payload      MEDIUMTEXT   NOT NULL,                             -- the exact JSON published
    request_id   VARCHAR(64)  NULL,
    created_at   DATETIME(6)  NOT NULL,
    published_at DATETIME(6)  NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uq_outbox_events_event_id (event_id),
    -- Relay: WHERE published_at IS NULL ORDER BY id, without a filesort
    KEY ix_outbox_events_published_at_id (published_at, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Local copy of the users known to the Users service, fed from user.events.v1; POST checks it
CREATE TABLE known_users (
    user_id       VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    registered_at DATETIME(6) NOT NULL,

    PRIMARY KEY (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
