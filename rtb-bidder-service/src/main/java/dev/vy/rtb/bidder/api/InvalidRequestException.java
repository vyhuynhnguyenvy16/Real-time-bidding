package dev.vy.rtb.bidder.api;

/** The exchange sent something we cannot read; answered with HTTP 400, never with a no-bid. */
class InvalidRequestException extends RuntimeException {
  InvalidRequestException(String detail) {
    super(detail);
  }
}
