package dev.vy.rtb.campaign.api;

/** A request parameter or header is unusable; becomes 400 VALIDATION_ERROR on {@code field}. */
public final class BadRequestException extends RuntimeException {

  private final String field;

  public BadRequestException(String field, String message) {
    super(message);
    this.field = field;
  }

  public String field() {
    return field;
  }
}
