package dev.vy.rtb.campaign.persistence;

import java.time.Instant;
import java.util.UUID;

/** Position after which the next page starts: campaigns are ordered by (createdAt, id). */
public record CampaignCursor(Instant createdAt, UUID id) {}
