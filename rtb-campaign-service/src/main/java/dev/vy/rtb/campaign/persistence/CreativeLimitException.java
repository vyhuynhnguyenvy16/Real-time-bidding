package dev.vy.rtb.campaign.persistence;

import java.util.UUID;

/** A campaign already holds the maximum number of non-archived creatives. */
public final class CreativeLimitException extends RuntimeException {

  private final int limit;

  public CreativeLimitException(UUID campaignId, int limit) {
    super("Campaign " + campaignId + " already has " + limit + " creatives");
    this.limit = limit;
  }

  public int limit() {
    return limit;
  }
}
