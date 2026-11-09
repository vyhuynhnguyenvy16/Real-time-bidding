package dev.vy.rtb.campaign.api;

import dev.vy.rtb.campaign.domain.Creative;
import dev.vy.rtb.campaign.persistence.Stored;
import java.time.Instant;
import java.util.UUID;

/** A creative as the API shows it. */
public record CreativeResponse(
    UUID id,
    UUID campaignId,
    String name,
    int width,
    int height,
    String markup,
    String landingUrl,
    String status,
    long version,
    Instant createdAt,
    Instant updatedAt) {

  static CreativeResponse from(Stored<Creative> stored) {
    Creative c = stored.value();
    return new CreativeResponse(
        c.id(),
        c.campaignId(),
        c.name(),
        c.width(),
        c.height(),
        c.markup(),
        c.landingUrl(),
        c.status().name(),
        stored.version(),
        stored.createdAt(),
        stored.updatedAt());
  }
}
