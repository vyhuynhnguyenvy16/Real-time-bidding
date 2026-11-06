package dev.vy.rtb.campaign.domain;

/** Lifecycle of a campaign; the state machine is in docs/data-model.md section 1.3. */
public enum CampaignStatus {
  DRAFT,
  ACTIVE,
  PAUSED,
  COMPLETED,
  ARCHIVED
}
