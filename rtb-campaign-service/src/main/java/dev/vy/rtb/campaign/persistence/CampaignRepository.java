package dev.vy.rtb.campaign.persistence;

import dev.vy.rtb.campaign.domain.Campaign;
import dev.vy.rtb.campaign.domain.CampaignStatus;
import java.util.Optional;
import java.util.UUID;

/**
 * Storage of campaigns. Every read and write is scoped by advertiser, so a campaign of another
 * tenant is simply not found (api-contract section 3: "404, never reveal it exists").
 */
public interface CampaignRepository {

  /** Stores a new campaign at version 1. Fails if the id exists or the advertiser is unknown. */
  Stored<Campaign> insert(Campaign campaign);

  Optional<Stored<Campaign>> findById(UUID advertiserId, UUID campaignId);

  /**
   * Like {@link #findById} but locks the campaign row until the surrounding transaction ends, so a
   * check that depends on other tables (creatives) and the write that follows cannot interleave
   * with another such check. Call it inside a transaction.
   */
  Optional<Stored<Campaign>> findByIdForUpdate(UUID advertiserId, UUID campaignId);

  /**
   * Replaces the stored campaign with {@code changed} if the row is still at {@code
   * current.version()}; the new version is {@code current.version() + 1}.
   *
   * @throws VersionConflictException when the version is stale or the row is not the advertiser's
   * @throws IllegalArgumentException when {@code changed} has a different id or advertiser
   */
  Stored<Campaign> update(Stored<Campaign> current, Campaign changed);

  /**
   * Campaigns of one advertiser, oldest first, optionally only those in {@code status}. {@code
   * after} is the {@code next} cursor of the previous page, or null for the first page.
   *
   * @param limit between 1 and 200
   */
  CampaignPage list(UUID advertiserId, CampaignStatus status, CampaignCursor after, int limit);
}
