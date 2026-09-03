CREATE TABLE xray_request (
    id                 UUID PRIMARY KEY,
    user_id            VARCHAR NOT NULL,
    original_filename  VARCHAR NOT NULL,
    storage_path       VARCHAR NOT NULL,
    status             VARCHAR NOT NULL,
    retry_count        INTEGER NOT NULL DEFAULT 0,
    result_pneumonia   BOOLEAN,
    result_confidence  DOUBLE PRECISION,
    model_version      VARCHAR,
    created_at         TIMESTAMP NOT NULL DEFAULT now(),
    updated_at         TIMESTAMP NOT NULL DEFAULT now(),
    processed_at       TIMESTAMP
);

CREATE INDEX idx_xray_request_user_id ON xray_request (user_id);
CREATE INDEX idx_xray_request_user_id_created_at ON xray_request (user_id, created_at DESC);
CREATE INDEX idx_xray_request_status ON xray_request (status);
