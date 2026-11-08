package dev.vy.rtb.campaign.domain;

/** A creative transition or edit that its status does not allow (an archived one is final). */
public final class CreativeStateException extends RuntimeException {

  private final CreativeStatus currentStatus;

  public CreativeStateException(CreativeStatus currentStatus, String action) {
    super("Cannot " + action + " a creative in status " + currentStatus);
    this.currentStatus = currentStatus;
  }

  public CreativeStatus currentStatus() {
    return currentStatus;
  }
}
