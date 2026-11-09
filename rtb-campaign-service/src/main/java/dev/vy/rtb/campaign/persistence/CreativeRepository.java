package dev.vy.rtb.campaign.persistence;

import dev.vy.rtb.campaign.domain.Creative;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Storage of creatives. Every read and write is scoped by advertiser and campaign. */
public interface CreativeRepository extends CreativeCounter {

  int MAX_PER_CAMPAIGN = 20;

  /**
   * Stores a new creative at version 1.
   *
   * @throws CreativeLimitException when the campaign already has {@link #MAX_PER_CAMPAIGN}
   *     non-archived creatives
   */
  Stored<Creative> insert(Creative creative);

  Optional<Stored<Creative>> findById(UUID advertiserId, UUID campaignId, UUID creativeId);

  /** All creatives of the campaign, oldest first; at most a few dozen, so no paging. */
  List<Stored<Creative>> listByCampaign(UUID advertiserId, UUID campaignId);

  /**
   * Replaces the stored creative if the row is still at {@code current.version()}.
   *
   * @throws VersionConflictException when the version is stale or the row is not the caller's
   */
  Stored<Creative> update(Stored<Creative> current, Creative changed);
}
