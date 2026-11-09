package dev.vy.rtb.campaign.api;

/** Pausing or archiving this creative would leave an ACTIVE campaign with nothing to bid with. */
public final class LastActiveCreativeException extends RuntimeException {
  public LastActiveCreativeException() {
    super("This is the last active creative of an active campaign; pause the campaign first");
  }
}
