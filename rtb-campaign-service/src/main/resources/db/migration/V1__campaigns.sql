-- Control plane: advertisers, campaigns, targeting.
-- Money is bigint micro-USD. The CHECK constraints repeat the domain rules (api-contract 3.3) so
-- that no other writer can store a row the domain would reject.

CREATE TABLE advertisers (
    id         uuid PRIMARY KEY,
    name       text        NOT NULL CHECK (char_length(name) BETWEEN 1 AND 120),
    status     text        NOT NULL CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE campaigns (
    id                  uuid PRIMARY KEY,
    advertiser_id       uuid        NOT NULL REFERENCES advertisers (id),
    name                text        NOT NULL,
    status              text        NOT NULL,
    daily_budget_micros bigint      NOT NULL,
    total_budget_micros bigint      NOT NULL,
    max_bid_cpm_micros  bigint      NOT NULL,
    bid_strategy        text        NOT NULL,
    fixed_cpm_micros    bigint,
    target_cpc_micros   bigint,
    pacing_mode         text        NOT NULL,
    freq_cap_count      integer,
    start_at            timestamptz NOT NULL,
    end_at              timestamptz NOT NULL,
    version             bigint      NOT NULL,
    created_at          timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL,

    CONSTRAINT campaigns_name_length CHECK (char_length(name) BETWEEN 1 AND 120 AND btrim(name) <> ''),
    CONSTRAINT campaigns_status CHECK (status IN ('DRAFT', 'ACTIVE', 'PAUSED', 'COMPLETED', 'ARCHIVED')),
    CONSTRAINT campaigns_daily_budget_positive CHECK (daily_budget_micros > 0),
    CONSTRAINT campaigns_total_covers_daily CHECK (total_budget_micros >= daily_budget_micros),
    CONSTRAINT campaigns_max_bid_within_daily CHECK (max_bid_cpm_micros > 0 AND max_bid_cpm_micros <= daily_budget_micros),
    CONSTRAINT campaigns_pacing_mode CHECK (pacing_mode IN ('EVEN', 'ASAP')),
    CONSTRAINT campaigns_freq_cap CHECK (freq_cap_count IS NULL OR freq_cap_count >= 1),
    CONSTRAINT campaigns_window CHECK (end_at > start_at),
    CONSTRAINT campaigns_version_positive CHECK (version >= 1),
    CONSTRAINT campaigns_bid_strategy CHECK (
        (bid_strategy = 'FIXED_CPM'
            AND fixed_cpm_micros > 0 AND fixed_cpm_micros <= max_bid_cpm_micros
            AND target_cpc_micros IS NULL)
        OR
        (bid_strategy = 'TARGET_CPC'
            AND target_cpc_micros > 0
            AND fixed_cpm_micros IS NULL)
    )
);

-- Keyset pagination: ORDER BY created_at, id within one advertiser.
CREATE INDEX campaigns_advertiser_created ON campaigns (advertiser_id, created_at, id);

CREATE TABLE campaign_targeting (
    campaign_id         uuid PRIMARY KEY REFERENCES campaigns (id),
    countries           text[]     NOT NULL DEFAULT '{}',
    device_types        smallint[] NOT NULL DEFAULT '{}',
    categories          text[]     NOT NULL DEFAULT '{}',
    publisher_blocklist text[]     NOT NULL DEFAULT '{}',
    -- 7 days x 24 hours; bit 0 = Monday 00:00 (Asia/Ho_Chi_Minh), all ones = always.
    schedule_mask       bit(168)   NOT NULL
);
