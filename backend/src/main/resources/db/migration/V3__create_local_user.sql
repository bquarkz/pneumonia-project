CREATE TABLE local_user (
    id           VARCHAR PRIMARY KEY,
    email        VARCHAR,
    display_name VARCHAR,
    created_at   TIMESTAMP NOT NULL DEFAULT now()
);
