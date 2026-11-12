package dev.vy.rtb.bidder.match;

import dev.vy.rtb.bidder.snapshot.BidderCampaign;
import dev.vy.rtb.bidder.snapshot.BidderCreative;
import dev.vy.rtb.common.NoBidReason;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * Chooses a campaign and creative for one request. Pure: no clock, no I/O, no shared state.
 *
 * <p>The filters run in a fixed order over the whole candidate list. When a filter leaves nobody,
 * its reason is the no-bid reason, so the reason always names the stage that ended the search.
 */
public final class Matcher {

  /** The schedule bits are in this zone (docs/data-model.md 1.2). */
  static final ZoneId SCHEDULE_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

  private Matcher() {}

  public static Decision decide(List<BidderCampaign> campaigns, BidRequest request, Instant now) {
    List<BidderCampaign> candidates = campaigns;

    candidates = candidates.stream().filter(c -> biddable(c, request, now)).toList();
    if (candidates.isEmpty()) {
      return new Decision.NoBid(NoBidReason.NO_CANDIDATE);
    }
    int hour = hourOfWeek(now);
    StageResult[] stages = {
      new StageResult(
          NoBidReason.GEO_MISMATCH,
          c ->
              c.countries().isEmpty()
                  || (request.country() != null && c.countries().contains(request.country()))),
      new StageResult(
          NoBidReason.DEVICE_MISMATCH,
          c ->
              c.deviceTypes().isEmpty()
                  || (request.deviceType() != null
                      && c.deviceTypes().contains(request.deviceType()))),
      new StageResult(
          NoBidReason.CATEGORY_MISMATCH,
          c ->
              c.categories().isEmpty()
                  || !Collections.disjoint(c.categories(), request.categories())),
      new StageResult(
          NoBidReason.BLOCKED_PUBLISHER,
          c ->
              request.publisherId() == null
                  || !c.publisherBlocklist().contains(request.publisherId())),
      new StageResult(NoBidReason.OUT_OF_SCHEDULE, c -> c.scheduleMask().charAt(hour) == '1'),
      new StageResult(NoBidReason.BELOW_FLOOR, c -> price(c) >= request.floorCpmMicros()),
    };
    for (StageResult stage : stages) {
      candidates = candidates.stream().filter(stage.keep()).toList();
      if (candidates.isEmpty()) {
        return new Decision.NoBid(stage.reason());
      }
    }

    BidderCampaign winner =
        candidates.stream()
            .min(
                Comparator.<BidderCampaign>comparingLong(Matcher::price)
                    .reversed()
                    .thenComparingInt(c -> spread(request.id(), c.id().toString())))
            .orElseThrow();
    List<BidderCreative> fitting = creativesFor(winner, request);
    BidderCreative creative = fitting.get(Math.floorMod(spread(request.id(), ""), fitting.size()));
    return new Decision.Bid(
        winner.id(), creative.id(), price(winner), creative.markup(), creative.landingUrl());
  }

  private record StageResult(NoBidReason reason, Predicate<BidderCampaign> keep) {}

  private static boolean biddable(BidderCampaign c, BidRequest request, Instant now) {
    return c.fixedCpmMicros() != null
        && !now.isBefore(c.startAt())
        && now.isBefore(c.endAt())
        && !creativesFor(c, request).isEmpty();
  }

  private static List<BidderCreative> creativesFor(BidderCampaign c, BidRequest request) {
    return c.creatives().stream()
        .filter(cr -> cr.width() == request.width() && cr.height() == request.height())
        .toList();
  }

  private static long price(BidderCampaign c) {
    return Math.min(c.maxBidCpmMicros(), c.fixedCpmMicros());
  }

  /** Monday 00:00 is hour 0, Sunday 23:00 is hour 167, as in the campaign schedule. */
  private static int hourOfWeek(Instant now) {
    ZonedDateTime local = now.atZone(SCHEDULE_ZONE);
    DayOfWeek day = local.getDayOfWeek();
    return (day.getValue() - 1) * 24 + local.getHour();
  }

  /** A stable pseudo-random number: the same request and campaign always give the same value. */
  private static int spread(String requestId, String salt) {
    int h = (requestId + "|" + salt).hashCode();
    return h ^ (h >>> 16);
  }
}
