package dev.vy.rtb.campaign.domain;

/** ACTIVE and PAUSED toggle freely; ARCHIVED is final (history is kept for reports). */
public enum CreativeStatus {
  ACTIVE,
  PAUSED,
  ARCHIVED
}
