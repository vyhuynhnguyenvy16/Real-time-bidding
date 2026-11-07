package dev.vy.rtb.campaign.domain;

import java.util.List;

/** Thrown when input breaks one or more rules; carries all violations, not just the first. */
public final class CampaignValidationException extends RuntimeException {

  private final List<Violation> violations;

  public CampaignValidationException(List<Violation> violations) {
    super("Campaign validation failed: " + violations);
    this.violations = List.copyOf(violations);
  }

  public List<Violation> violations() {
    return violations;
  }
}
