package dev.vy.rtb.campaign.outbox;

import dev.vy.rtb.campaign.domain.BidStrategy;
import dev.vy.rtb.campaign.domain.Campaign;
import dev.vy.rtb.campaign.domain.CampaignStatus;
import dev.vy.rtb.campaign.domain.Creative;
import dev.vy.rtb.campaign.domain.CreativeStatus;
import dev.vy.rtb.campaign.domain.Targeting;
import dev.vy.rtb.campaign.persistence.Stored;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Builds the event for a campaign. Pure: no database, no clock. */
public final class CampaignEventFactory {

  /** The schedule bits are always in this zone (docs/data-model.md 1.2). */
  static final String SCHEDULE_TIMEZONE = "Asia/Ho_Chi_Minh";

  private CampaignEventFactory() {}

  /**
   * @return the event, or empty for a tombstone (an ARCHIVED campaign leaves the bidder for good).
   *     PAUSED and COMPLETED campaigns still get a full event so the bidder stops bidding on them.
   */
  public static Optional<CampaignEvent> build(
      Stored<Campaign> campaign,
      List<Stored<Creative>> creatives,
      long revision,
      Instant emittedAt) {
    Campaign c = campaign.value();
    if (c.status() == CampaignStatus.ARCHIVED) {
      return Optional.empty();
    }
    BidStrategy strategy = c.bidStrategy();
    boolean fixed = strategy.type() == BidStrategy.Type.FIXED_CPM;
    Targeting t = c.targeting();
    CampaignEvent.CampaignView view =
        new CampaignEvent.CampaignView(
            c.id(),
            c.advertiserId(),
            c.name(),
            c.status().name(),
            c.dailyBudget().micros(),
            c.totalBudget().micros(),
            c.maxBidCpm().micros(),
            new CampaignEvent.BidStrategyView(
                strategy.type().name(),
                fixed ? strategy.valueMicros() : null,
                fixed ? null : strategy.valueMicros()),
            c.pacingMode().name(),
            c.freqCapCount(),
            c.startAt().toString(),
            c.endAt().toString(),
            campaign.version(),
            new CampaignEvent.TargetingView(
                t.countries(),
                t.deviceTypes(),
                t.categories(),
                t.publisherBlocklist(),
                SCHEDULE_TIMEZONE,
                t.schedule().toBitString()));
    List<CampaignEvent.CreativeView> active =
        creatives.stream()
            .filter(s -> s.value().status() == CreativeStatus.ACTIVE)
            .map(
                s ->
                    new CampaignEvent.CreativeView(
                        s.value().id(),
                        s.value().name(),
                        s.value().width(),
                        s.value().height(),
                        s.value().markup(),
                        s.value().landingUrl(),
                        s.version()))
            .toList();
    return Optional.of(
        new CampaignEvent(
            CampaignEvent.SCHEMA_VERSION, revision, emittedAt.toString(), view, active));
  }
}
