package dev.vy.rtb.campaign.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * The creation rules of docs/api-contract.md section 3.3. A cross-field rule is skipped when one of
 * the fields it compares is already invalid, so one mistake gives one violation, not a cascade.
 */
final class CampaignValidator {

  private static final int MAX_NAME_LENGTH = 120;

  private CampaignValidator() {}

  static List<Violation> validate(NewCampaign in) {
    List<Violation> out = new ArrayList<>();

    boolean nameOk = in.name() != null && !in.name().isBlank() && in.name().length() <= MAX_NAME_LENGTH;
    if (!nameOk) {
      out.add(new Violation("name", "must be 1-" + MAX_NAME_LENGTH + " characters"));
    }

    boolean dailyOk = in.dailyBudgetMicros() > 0;
    if (!dailyOk) {
      out.add(new Violation("dailyBudgetMicros", "must be greater than 0"));
    }
    if (dailyOk && in.totalBudgetMicros() < in.dailyBudgetMicros()) {
      out.add(new Violation("totalBudgetMicros", "must be at least dailyBudgetMicros"));
    }

    boolean maxBidOk = in.maxBidCpmMicros() > 0;
    if (!maxBidOk) {
      out.add(new Violation("maxBidCpmMicros", "must be greater than 0"));
    } else if (dailyOk && in.maxBidCpmMicros() > in.dailyBudgetMicros()) {
      out.add(new Violation("maxBidCpmMicros", "the cost of 1000 impressions exceeds dailyBudgetMicros"));
    }

    validateStrategy(in, maxBidOk, out);

    if (in.freqCapCount() != null && in.freqCapCount() < 1) {
      out.add(new Violation("freqCapCount", "must be null or at least 1"));
    }

    if (in.startAt() == null) {
      out.add(new Violation("startAt", "is required"));
    }
    if (in.endAt() == null) {
      out.add(new Violation("endAt", "is required"));
    } else if (in.startAt() != null && !in.endAt().isAfter(in.startAt())) {
      out.add(new Violation("endAt", "must be after startAt"));
    }
    return out;
  }

  private static void validateStrategy(NewCampaign in, boolean maxBidOk, List<Violation> out) {
    BidStrategy strategy = in.bidStrategy();
    if (strategy == null) {
      out.add(new Violation("bidStrategy", "is required"));
    } else if (strategy.valueMicros() <= 0) {
      out.add(new Violation("bidStrategy", "price must be greater than 0"));
    } else if (strategy.type() == BidStrategy.Type.FIXED_CPM
        && maxBidOk
        && strategy.valueMicros() > in.maxBidCpmMicros()) {
      out.add(new Violation("bidStrategy", "fixed CPM must not exceed maxBidCpmMicros"));
    }
  }
}
