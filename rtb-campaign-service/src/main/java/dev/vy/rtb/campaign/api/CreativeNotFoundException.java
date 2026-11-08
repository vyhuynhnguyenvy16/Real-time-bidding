package dev.vy.rtb.campaign.api;

/** The creative does not exist in that campaign, or the campaign is not the caller's. */
public final class CreativeNotFoundException extends RuntimeException {
  public CreativeNotFoundException() {
    super("Creative not found");
  }
}
