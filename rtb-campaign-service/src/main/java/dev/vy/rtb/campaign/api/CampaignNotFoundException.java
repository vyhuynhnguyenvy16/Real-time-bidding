package dev.vy.rtb.campaign.api;

/** The campaign does not exist, or belongs to another advertiser: the API never tells which. */
public final class CampaignNotFoundException extends RuntimeException {
  public CampaignNotFoundException() {
    super("Campaign not found");
  }
}
