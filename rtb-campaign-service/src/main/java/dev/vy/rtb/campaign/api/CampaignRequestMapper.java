package dev.vy.rtb.campaign.api;

import dev.vy.rtb.campaign.domain.BidStrategy;
import dev.vy.rtb.campaign.domain.CampaignValidationException;
import dev.vy.rtb.campaign.domain.NewCampaign;
import dev.vy.rtb.campaign.domain.PacingMode;
import dev.vy.rtb.campaign.domain.TargetingInput;
import dev.vy.rtb.campaign.domain.Violation;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns the JSON shape into the domain input. Only translation problems (an unknown enum name, a
 * foreign time zone) are reported here; business rules stay in the domain.
 */
final class CampaignRequestMapper {

  private CampaignRequestMapper() {}

  static NewCampaign toNewCampaign(CampaignRequest request) {
    List<Violation> problems = new ArrayList<>();
    BidStrategy strategy = strategy(request.bidStrategy(), problems);
    PacingMode pacing = pacing(request.pacingMode(), problems);
    TargetingInput targeting = targeting(request.targeting(), problems);
    if (!problems.isEmpty()) {
      throw new CampaignValidationException(problems);
    }
    return new NewCampaign(
        request.name(),
        orZero(request.dailyBudgetMicros()),
        orZero(request.totalBudgetMicros()),
        orZero(request.maxBidCpmMicros()),
        strategy,
        pacing,
        request.freqCapCount(),
        request.startAt(),
        request.endAt(),
        targeting);
  }

  /** A missing amount becomes 0, which the domain then reports as an invalid amount. */
  private static long orZero(Long value) {
    return value == null ? 0 : value;
  }

  private static BidStrategy strategy(
      CampaignRequest.BidStrategyRequest in, List<Violation> problems) {
    if (in == null || in.type() == null) {
      return null; // the domain reports "bidStrategy is required"
    }
    return switch (in.type()) {
      case "FIXED_CPM" -> BidStrategy.fixedCpm(orZero(in.cpmMicros()));
      case "TARGET_CPC" -> BidStrategy.targetCpc(orZero(in.cpcMicros()));
      default -> {
        problems.add(new Violation("bidStrategy.type", "must be FIXED_CPM or TARGET_CPC"));
        yield null;
      }
    };
  }

  private static PacingMode pacing(String in, List<Violation> problems) {
    if (in == null) {
      return PacingMode.EVEN; // data-model 1.2: the default
    }
    try {
      return PacingMode.valueOf(in);
    } catch (IllegalArgumentException e) {
      problems.add(new Violation("pacingMode", "must be EVEN or ASAP"));
      return null;
    }
  }

  private static TargetingInput targeting(
      CampaignRequest.TargetingRequest in, List<Violation> problems) {
    if (in == null) {
      return null;
    }
    var schedule = in.schedule();
    if (schedule != null
        && schedule.timezone() != null
        && !CampaignResponse.TIMEZONE.equals(schedule.timezone())) {
      problems.add(
          new Violation(
              "targeting.schedule.timezone",
              "only " + CampaignResponse.TIMEZONE + " is supported"));
    }
    return new TargetingInput(
        in.countries(),
        in.deviceTypes(),
        in.categories(),
        in.publisherBlocklist(),
        schedule == null ? null : schedule.hours());
  }
}
