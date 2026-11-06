package dev.vy.rtb.campaign.domain;

import dev.vy.rtb.common.Money;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A campaign. Immutable: every change returns a new instance. Built only through {@link #draft}. */
public final class Campaign {

  private final UUID id;
  private final UUID advertiserId;
  private final String name;
  private final CampaignStatus status;
  private final Money dailyBudget;
  private final Money totalBudget;
  private final Money maxBidCpm;
  private final BidStrategy bidStrategy;
  private final PacingMode pacingMode;
  private final Integer freqCapCount;
  private final Instant startAt;
  private final Instant endAt;

  private Campaign(UUID id, UUID advertiserId, NewCampaign in, CampaignStatus status) {
    this.id = id;
    this.advertiserId = advertiserId;
    this.name = in.name();
    this.status = status;
    this.dailyBudget = Money.ofMicros(in.dailyBudgetMicros());
    this.totalBudget = Money.ofMicros(in.totalBudgetMicros());
    this.maxBidCpm = Money.ofMicros(in.maxBidCpmMicros());
    this.bidStrategy = in.bidStrategy();
    this.pacingMode = in.pacingMode();
    this.freqCapCount = in.freqCapCount();
    this.startAt = in.startAt();
    this.endAt = in.endAt();
  }

  /** Creates a DRAFT campaign, or throws {@link CampaignValidationException} with every violation. */
  public static Campaign draft(UUID id, UUID advertiserId, NewCampaign input) {
    List<Violation> violations = CampaignValidator.validate(input);
    if (!violations.isEmpty()) {
      throw new CampaignValidationException(violations);
    }
    return new Campaign(id, advertiserId, input, CampaignStatus.DRAFT);
  }

  public UUID id() { return id; }
  public UUID advertiserId() { return advertiserId; }
  public String name() { return name; }
  public CampaignStatus status() { return status; }
  public Money dailyBudget() { return dailyBudget; }
  public Money totalBudget() { return totalBudget; }
  public Money maxBidCpm() { return maxBidCpm; }
  public BidStrategy bidStrategy() { return bidStrategy; }
  public PacingMode pacingMode() { return pacingMode; }
  public Integer freqCapCount() { return freqCapCount; }
  public Instant startAt() { return startAt; }
  public Instant endAt() { return endAt; }
}
