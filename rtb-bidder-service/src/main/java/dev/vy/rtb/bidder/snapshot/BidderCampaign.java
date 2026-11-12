package dev.vy.rtb.bidder.snapshot;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * An ACTIVE campaign as the bidder sees it. Immutable. Empty targeting sets mean "no restriction".
 * {@code fixedCpmMicros} is null for a TARGET_CPC campaign, which does not bid yet.
 */
public record BidderCampaign(
    UUID id,
    long maxBidCpmMicros,
    Long fixedCpmMicros,
    Instant startAt,
    Instant endAt,
    Set<String> countries,
    Set<Integer> deviceTypes,
    Set<String> categories,
    Set<String> publisherBlocklist,
    String scheduleMask,
    List<BidderCreative> creatives) {

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private UUID id;
    private long maxBidCpmMicros;
    private Long fixedCpmMicros;
    private Instant startAt;
    private Instant endAt;
    private Set<String> countries = Set.of();
    private Set<Integer> deviceTypes = Set.of();
    private Set<String> categories = Set.of();
    private Set<String> publisherBlocklist = Set.of();
    private String scheduleMask = "1".repeat(168);
    private List<BidderCreative> creatives = List.of();

    public Builder id(UUID id) {
      this.id = id;
      return this;
    }

    public Builder maxBidCpmMicros(long value) {
      this.maxBidCpmMicros = value;
      return this;
    }

    public Builder fixedCpmMicros(Long value) {
      this.fixedCpmMicros = value;
      return this;
    }

    public Builder window(Instant startAt, Instant endAt) {
      this.startAt = startAt;
      this.endAt = endAt;
      return this;
    }

    public Builder countries(Set<String> value) {
      this.countries = value;
      return this;
    }

    public Builder deviceTypes(Set<Integer> value) {
      this.deviceTypes = value;
      return this;
    }

    public Builder categories(Set<String> value) {
      this.categories = value;
      return this;
    }

    public Builder publisherBlocklist(Set<String> value) {
      this.publisherBlocklist = value;
      return this;
    }

    public Builder scheduleMask(String value) {
      this.scheduleMask = value;
      return this;
    }

    public Builder creatives(List<BidderCreative> value) {
      this.creatives = value;
      return this;
    }

    public BidderCampaign build() {
      return new BidderCampaign(
          id,
          maxBidCpmMicros,
          fixedCpmMicros,
          startAt,
          endAt,
          Set.copyOf(countries),
          Set.copyOf(deviceTypes),
          Set.copyOf(categories),
          Set.copyOf(publisherBlocklist),
          scheduleMask,
          List.copyOf(creatives));
    }
  }
}
