package dev.vy.rtb.campaign.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.vy.rtb.campaign.domain.BidStrategy;
import dev.vy.rtb.campaign.domain.Campaign;
import dev.vy.rtb.campaign.domain.Targeting;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A campaign as the advertiser sends it (docs/api-contract.md 3.2). Fields the server owns (id,
 * advertiserId, status, version, timestamps) are not here, so a client cannot set them.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CampaignRequest(
    String name,
    Long dailyBudgetMicros,
    Long totalBudgetMicros,
    Long maxBidCpmMicros,
    BidStrategyRequest bidStrategy,
    String pacingMode,
    Integer freqCapCount,
    Instant startAt,
    Instant endAt,
    TargetingRequest targeting) {

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record BidStrategyRequest(String type, Long cpmMicros, Long cpcMicros) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record TargetingRequest(
      List<String> countries,
      List<Integer> deviceTypes,
      List<String> categories,
      List<String> publisherBlocklist,
      ScheduleRequest schedule) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ScheduleRequest(String timezone, Map<String, List<Integer>> hours) {}

  /** The editable view of a stored campaign; the base a PATCH is merged onto. */
  static CampaignRequest from(Campaign c) {
    BidStrategy strategy = c.bidStrategy();
    boolean fixed = strategy.type() == BidStrategy.Type.FIXED_CPM;
    Targeting t = c.targeting();
    return new CampaignRequest(
        c.name(),
        c.dailyBudget().micros(),
        c.totalBudget().micros(),
        c.maxBidCpm().micros(),
        new BidStrategyRequest(
            strategy.type().name(),
            fixed ? strategy.valueMicros() : null,
            fixed ? null : strategy.valueMicros()),
        c.pacingMode().name(),
        c.freqCapCount(),
        c.startAt(),
        c.endAt(),
        new TargetingRequest(
            t.countries(),
            t.deviceTypes(),
            t.categories(),
            t.publisherBlocklist(),
            new ScheduleRequest(
                CampaignResponse.TIMEZONE, CampaignResponse.hoursByDay(t.schedule()))));
  }
}
