package dev.vy.rtb.campaign.persistence;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock the test moves by hand, so created_at and updated_at are known exactly. */
final class MutableClock extends Clock {

  private Instant now;

  MutableClock(Instant start) {
    this.now = start;
  }

  void advance(Duration duration) {
    now = now.plus(duration);
  }

  void set(Instant instant) {
    now = instant;
  }

  @Override
  public ZoneId getZone() {
    return ZoneOffset.UTC;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    return this;
  }

  @Override
  public Instant instant() {
    return now;
  }
}
