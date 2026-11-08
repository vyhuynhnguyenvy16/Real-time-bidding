-- Creatives (banners) of a campaign. The CHECKs repeat the domain rules (CreativeValidator) so a
-- writer that bypasses the domain still cannot store a creative it would reject.

-- Lets creatives reference (campaign, advertiser) together: a creative can never point at a
-- campaign of another tenant, whatever the application does.
ALTER TABLE campaigns ADD CONSTRAINT campaigns_id_advertiser UNIQUE (id, advertiser_id);

CREATE TABLE creatives (
    id            uuid PRIMARY KEY,
    campaign_id   uuid        NOT NULL,
    advertiser_id uuid        NOT NULL,
    name          text        NOT NULL,
    width         integer     NOT NULL,
    height        integer     NOT NULL,
    markup        text        NOT NULL,
    landing_url   text        NOT NULL,
    status        text        NOT NULL,
    version       bigint      NOT NULL,
    created_at    timestamptz NOT NULL,
    updated_at    timestamptz NOT NULL,

    CONSTRAINT creatives_campaign_owner FOREIGN KEY (campaign_id, advertiser_id)
        REFERENCES campaigns (id, advertiser_id),
    CONSTRAINT creatives_name_length CHECK (char_length(name) BETWEEN 1 AND 120 AND btrim(name) <> ''),
    CONSTRAINT creatives_size CHECK ((width, height) IN ((300, 250), (728, 90), (320, 50), (300, 600))),
    CONSTRAINT creatives_status CHECK (status IN ('ACTIVE', 'PAUSED', 'ARCHIVED')),
    CONSTRAINT creatives_version_positive CHECK (version >= 1),
    CONSTRAINT creatives_markup_size CHECK (octet_length(markup) BETWEEN 1 AND 20480),
    -- \y is a word boundary; ~* is case-insensitive. Same patterns as CreativeValidator.
    CONSTRAINT creatives_markup_safe CHECK (
        markup !~* '<\s*/?\s*(script|iframe|object|embed)\y'
        AND markup !~* 'javascript\s*:'
        AND markup !~* '[\s"''/]on[a-z]+\s*='),
    CONSTRAINT creatives_landing_url CHECK (
        char_length(landing_url) <= 2048 AND landing_url ~ '^https://[^/?#[:space:]]+')
);

CREATE INDEX creatives_campaign_created ON creatives (campaign_id, created_at, id);
-- Counting active creatives is on the activate path.
CREATE INDEX creatives_campaign_active ON creatives (campaign_id) WHERE status = 'ACTIVE';
