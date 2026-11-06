package dev.vy.rtb.campaign.domain;

/** A transition that the state machine (docs/data-model.md 1.3) or its preconditions do not allow. */
public final class InvalidStateException extends RuntimeException {

  private final CampaignStatus currentStatus;
  private final String action;

  public InvalidStateException(CampaignStatus currentStatus, String action, String reason) {
    super("Cannot " + action + " a campaign in status " + currentStatus + ": " + reason);
    this.currentStatus = currentStatus;
    this.action = action;
  }

  public CampaignStatus currentStatus() {
    return currentStatus;
  }

  public String action() {
    return action;
  }
}
