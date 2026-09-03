-- Schema for Spring Modulith's event publication registry (spring-modulith-events-jdbc),
-- read/written by the library's own hand-written JDBC repository. The -jdbc variant CAN
-- auto-create this schema at startup (spring.modulith.events.jdbc.schema-initialization.enabled),
-- but that's left off here - Flyway is this project's one authoritative schema source.
--
-- Column layout matches org/springframework/modulith/events/jdbc/schemas/v2/schema-postgresql.sql
-- bundled in spring-modulith-events-jdbc 2.1.1, except the indexes: that file's
-- `USING hash(serialized_event)` is Postgres-only syntax H2 rejects even in PostgreSQL
-- compatibility mode, and this same file also runs against H2 for the @ApplicationModuleTest
-- suite (see src/test/resources/application.yml) - a plain b-tree index costs a lookup
-- optimization, not a correctness guarantee, so it's the one intentional deviation.

CREATE TABLE event_publication (
    id                      UUID NOT NULL,
    listener_id             TEXT NOT NULL,
    event_type              TEXT NOT NULL,
    serialized_event        TEXT NOT NULL,
    publication_date        TIMESTAMP WITH TIME ZONE NOT NULL,
    completion_date         TIMESTAMP WITH TIME ZONE,
    status                  TEXT,
    completion_attempts     INT,
    last_resubmission_date  TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id)
);

CREATE INDEX event_publication_serialized_event_idx
    ON event_publication (serialized_event);
CREATE INDEX event_publication_by_completion_date_idx
    ON event_publication (completion_date);

CREATE TABLE event_publication_archive (
    id                      UUID NOT NULL,
    listener_id             TEXT NOT NULL,
    event_type              TEXT NOT NULL,
    serialized_event        TEXT NOT NULL,
    publication_date        TIMESTAMP WITH TIME ZONE NOT NULL,
    completion_date         TIMESTAMP WITH TIME ZONE,
    status                  TEXT,
    completion_attempts     INT,
    last_resubmission_date  TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id)
);

CREATE INDEX event_publication_archive_serialized_event_idx
    ON event_publication_archive (serialized_event);
CREATE INDEX event_publication_archive_by_completion_date_idx
    ON event_publication_archive (completion_date);
