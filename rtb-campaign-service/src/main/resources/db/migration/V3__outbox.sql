-- Transactional outbox (ADR-0003). A row is written in the same transaction as the campaign or
-- creative change it describes; the relay publishes rows in id order and deletes them once Kafka
-- has acknowledged. payload NULL is a tombstone (the campaign was archived).
-- No foreign key on purpose: a tombstone must outlive anything it refers to.
CREATE TABLE outbox (
    id          bigserial   PRIMARY KEY,
    campaign_id uuid        NOT NULL,
    payload     json,
    created_at  timestamptz NOT NULL DEFAULT now()
);
