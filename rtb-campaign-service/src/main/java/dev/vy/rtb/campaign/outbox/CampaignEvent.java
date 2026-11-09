package dev.vy.rtb.campaign.outbox;

import java.util.List;
import java.util.UUID;

/**
 * What the bidder reads from the {@code campaign-updates} topic (docs/adr/0003): one campaign with
 * its ACTIVE creatives, as a single self-contained document. Instants are ISO-8601 strings and
 * money is micro-USD, so the shape does not depend on how a mapper is configured. Adding optional
 * fields is compatible; anything else needs a new {@code schemaVersion}.
 */
public record CampaignEvent(
    int schemaVersion, String emittedAt, CampaignView campaign, List<CreativeView> creatives) {

  public static final int SCHEMA_VERSION = 1;

  public record CampaignView(
      UUID id,
      UUID advertiserId,
      String name,
      String status,
      long dailyBudgetMicros,
      long totalBudgetMicros,
      long maxBidCpmMicros,
      BidStrategyView bidStrategy,
      String pacingMode,
      Integer freqCapCount,
      String startAt,
      String endAt,
      long version,
      TargetingView targeting) {}

  public record BidStrategyView(String type, Long cpmMicros, Long cpcMicros) {}

  /** {@code scheduleMask} is 168 characters, bit 0 = Monday 00:00 in {@code scheduleTimezone}. */
  public record TargetingView(
      List<String> countries,
      List<Integer> deviceTypes,
      List<String> categories,
      List<String> publisherBlocklist,
      String scheduleTimezone,
      String scheduleMask) {}

  public record CreativeView(
      UUID id,
      String name,
      int width,
      int height,
      String markup,
      String landingUrl,
      long version) {}
}
