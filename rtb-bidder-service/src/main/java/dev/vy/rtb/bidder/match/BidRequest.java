package dev.vy.rtb.bidder.match;

import java.util.Set;

/**
 * The part of an OpenRTB bid request the bidder uses. {@code country}, {@code deviceType} and
 * {@code publisherId} are null when the exchange did not send them.
 */
public record BidRequest(
    String id,
    int width,
    int height,
    long floorCpmMicros,
    String country,
    Integer deviceType,
    Set<String> categories,
    String publisherId) {

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private String id;
    private int width;
    private int height;
    private long floorCpmMicros;
    private String country;
    private Integer deviceType;
    private Set<String> categories = Set.of();
    private String publisherId;

    public Builder id(String value) {
      this.id = value;
      return this;
    }

    public Builder size(int width, int height) {
      this.width = width;
      this.height = height;
      return this;
    }

    public Builder floorCpmMicros(long value) {
      this.floorCpmMicros = value;
      return this;
    }

    public Builder country(String value) {
      this.country = value;
      return this;
    }

    public Builder deviceType(Integer value) {
      this.deviceType = value;
      return this;
    }

    public Builder categories(Set<String> value) {
      this.categories = value;
      return this;
    }

    public Builder publisherId(String value) {
      this.publisherId = value;
      return this;
    }

    public BidRequest build() {
      return new BidRequest(
          id,
          width,
          height,
          floorCpmMicros,
          country,
          deviceType,
          Set.copyOf(categories),
          publisherId);
    }
  }
}
