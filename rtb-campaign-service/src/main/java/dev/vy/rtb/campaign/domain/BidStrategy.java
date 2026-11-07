package dev.vy.rtb.campaign.domain;

/**
 * How the bid price is chosen. {@code valueMicros} is a CPM for FIXED_CPM, a CPC for TARGET_CPC.
 */
public record BidStrategy(Type type, long valueMicros) {

  public enum Type {
    FIXED_CPM,
    TARGET_CPC
  }

  public static BidStrategy fixedCpm(long cpmMicros) {
    return new BidStrategy(Type.FIXED_CPM, cpmMicros);
  }

  public static BidStrategy targetCpc(long cpcMicros) {
    return new BidStrategy(Type.TARGET_CPC, cpcMicros);
  }
}
