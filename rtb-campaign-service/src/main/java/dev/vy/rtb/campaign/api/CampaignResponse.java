package dev.vy.rtb.campaign.api;

import dev.vy.rtb.campaign.domain.BidStrategy;
import dev.vy.rtb.campaign.domain.Campaign;
import dev.vy.rtb.campaign.domain.Schedule;
import dev.vy.rtb.campaign.domain.Targeting;
import dev.vy.rtb.campaign.persistence.Stored;
import java.time.DayOfWeek;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** A campaign as the API shows it (docs/api-contract.md 3.2). Money is always micro-USD. */
public record CampaignResponse(
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
    Instant startAt,
    Instant endAt,
    TargetingView targeting,
    long version,
    Instant createdAt,
    Instant updatedAt) {

  public record BidStrategyView(String type, Long cpmMicros, Long cpcMicros) {}

  public record TargetingView(
      List<String> countries,
      List<Integer> deviceTypes,
      List<String> categories,
      List<String> publisherBlocklist,
      ScheduleView schedule) {}

  /** {@code hours} maps "mon".."sun" to allowed hours; an empty map means "always". */
  public record ScheduleView(String timezone, Map<String, List<Integer>> hours) {}

  static final String TIMEZONE = "Asia/Ho_Chi_Minh";

  public static CampaignResponse from(Stored<Campaign> stored) {
    Campaign c = stored.value();
    BidStrategy strategy = c.bidStrategy();
    boolean fixed = strategy.type() == BidStrategy.Type.FIXED_CPM;
    return new CampaignResponse(
        c.id(),
        c.advertiserId(),
        c.name(),
        c.status().name(),
        c.dailyBudget().micros(),
        c.totalBudget().micros(),
        c.maxBidCpm().micros(),
        new BidStrategyView(
            strategy.type().name(),
            fixed ? strategy.valueMicros() : null,
            fixed ? null : strategy.valueMicros()),
        c.pacingMode().name(),
        c.freqCapCount(),
        c.startAt(),
        c.endAt(),
        targetingView(c.targeting()),
        stored.version(),
        stored.createdAt(),
        stored.updatedAt());
  }

  private static TargetingView targetingView(Targeting t) {
    return new TargetingView(
        t.countries(),
        t.deviceTypes(),
        t.categories(),
        t.publisherBlocklist(),
        new ScheduleView(TIMEZONE, hoursByDay(t.schedule())));
  }

  static Map<String, List<Integer>> hoursByDay(Schedule schedule) {
    Map<String, List<Integer>> hours = new LinkedHashMap<>();
    if (schedule.allowedHoursCount() == 7 * 24) {
      return hours;
    }
    for (DayOfWeek day : DayOfWeek.values()) {
      List<Integer> allowed = new ArrayList<>();
      for (int hour = 0; hour < 24; hour++) {
        if (schedule.allows(day, hour)) {
          allowed.add(hour);
        }
      }
      if (!allowed.isEmpty()) {
        hours.put(day.name().substring(0, 3).toLowerCase(Locale.ROOT), allowed);
      }
    }
    return hours;
  }
}
