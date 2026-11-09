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
    -- Same patterns, with the same ASCII classes, as CreativeValidator (CreativeParityTest keeps
    -- the two in step). ~* is case-insensitive. [ \t\n\r\f\v] is space, tab, LF, CR, FF, VT.
    CONSTRAINT creatives_markup_safe CHECK (
        markup !~* '<[ \t\n\r\f\v/]*(script|iframe|object|embed|base|meta|form|link|style|svg|math|frame|applet|template)'
        AND markup !~* 'j[ \t\n\r\f\v]*a[ \t\n\r\f\v]*v[ \t\n\r\f\v]*a[ \t\n\r\f\v]*s[ \t\n\r\f\v]*c[ \t\n\r\f\v]*r[ \t\n\r\f\v]*i[ \t\n\r\f\v]*p[ \t\n\r\f\v]*t[ \t\n\r\f\v]*:'
        AND markup !~* '&(#|(colon|tab|newline|lpar|rpar|sol|bsol);)'
        AND markup !~* '[ \t\n\r\f\v"''/]on[a-z]+[ \t\n\r\f\v]*='
        AND markup !~ '\$\{(?!(AUCTION_PRICE|CLICK_URL)\})'),
    -- https, at most 512 characters (it travels inside the click token), no user:password@.
    CONSTRAINT creatives_landing_url CHECK (
        char_length(landing_url) <= 512 AND landing_url ~ '^https://[^/?#@[:space:]]+([/?#]|$)')
);

CREATE INDEX creatives_campaign_created ON creatives (campaign_id, created_at, id);
-- Counting active creatives is on the activate path.
CREATE INDEX creatives_campaign_active ON creatives (campaign_id) WHERE status = 'ACTIVE';
