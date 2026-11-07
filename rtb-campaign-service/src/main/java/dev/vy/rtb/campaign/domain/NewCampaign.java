package dev.vy.rtb.campaign.domain;

import java.time.Instant;

/**
 * Everything an advertiser provides to create a campaign. Money is a plain {@code long} (micro-USD)
 * so a bad amount becomes a {@link Violation} instead of an exception from {@code Money}.
 */
public record NewCampaign(
    String name,
    long dailyBudgetMicros,
    long totalBudgetMicros,
    long maxBidCpmMicros,
    BidStrategy bidStrategy,
    PacingMode pacingMode,
    Integer freqCapCount,
    Instant startAt,
    Instant endAt,
    TargetingInput targeting) {

  public static Builder builder() {
    return new Builder();
  }

  /** Test-friendly builder: change one field of a valid input at a time. */
  public static final class Builder {
    private String name;
    private long dailyBudgetMicros;
    private long totalBudgetMicros;
    private long maxBidCpmMicros;
    private BidStrategy bidStrategy;
    private PacingMode pacingMode = PacingMode.EVEN;
    private Integer freqCapCount;
    private Instant startAt;
    private Instant endAt;
    private TargetingInput targeting;

    public Builder name(String v) {
      this.name = v;
      return this;
    }

    public Builder dailyBudgetMicros(long v) {
      this.dailyBudgetMicros = v;
      return this;
    }

    public Builder totalBudgetMicros(long v) {
      this.totalBudgetMicros = v;
      return this;
    }

    public Builder maxBidCpmMicros(long v) {
      this.maxBidCpmMicros = v;
      return this;
    }

    public Builder bidStrategy(BidStrategy v) {
      this.bidStrategy = v;
      return this;
    }

    public Builder pacingMode(PacingMode v) {
      this.pacingMode = v;
      return this;
    }

    public Builder freqCapCount(Integer v) {
      this.freqCapCount = v;
      return this;
    }

    public Builder startAt(Instant v) {
      this.startAt = v;
      return this;
    }

    public Builder endAt(Instant v) {
      this.endAt = v;
      return this;
    }

    public Builder targeting(TargetingInput v) {
      this.targeting = v;
      return this;
    }

    public NewCampaign build() {
      return new NewCampaign(
          name,
          dailyBudgetMicros,
          totalBudgetMicros,
          maxBidCpmMicros,
          bidStrategy,
          pacingMode,
          freqCapCount,
          startAt,
          endAt,
          targeting);
    }
  }
}
