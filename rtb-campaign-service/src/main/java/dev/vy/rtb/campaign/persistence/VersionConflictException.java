package dev.vy.rtb.campaign.persistence;

import java.util.UUID;

/**
 * An update was based on a version that is no longer current, or the campaign does not belong to
 * the advertiser. The caller should read again and retry.
 */
public final class VersionConflictException extends RuntimeException {

  private final UUID campaignId;
  private final long expectedVersion;

  public VersionConflictException(UUID campaignId, long expectedVersion) {
    super("Campaign " + campaignId + " is not at version " + expectedVersion);
    this.campaignId = campaignId;
    this.expectedVersion = expectedVersion;
  }

  public UUID campaignId() {
    return campaignId;
  }

  public long expectedVersion() {
    return expectedVersion;
  }
}
