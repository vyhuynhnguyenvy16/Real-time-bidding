package dev.vy.rtb.campaign.api;

/** A PATCH came without {@code If-Match}; becomes 428 PRECONDITION_REQUIRED. */
public final class PreconditionRequiredException extends RuntimeException {
  public PreconditionRequiredException() {
    super("If-Match is required: send the ETag of the version you are changing");
  }
}
