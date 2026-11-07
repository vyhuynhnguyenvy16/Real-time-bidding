package dev.vy.rtb.campaign.domain;

import dev.vy.rtb.common.Money;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A campaign. Immutable: every change returns a new instance. Built only through {@link #draft}.
 */
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
  private final Targeting targeting;

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
    this.targeting = TargetingRules.normalise(in.targeting());
  }

  private Campaign(Campaign other, CampaignStatus status) {
    this.id = other.id;
    this.advertiserId = other.advertiserId;
    this.name = other.name;
    this.status = status;
    this.dailyBudget = other.dailyBudget;
    this.totalBudget = other.totalBudget;
    this.maxBidCpm = other.maxBidCpm;
    this.bidStrategy = other.bidStrategy;
    this.pacingMode = other.pacingMode;
    this.freqCapCount = other.freqCapCount;
    this.startAt = other.startAt;
    this.endAt = other.endAt;
    this.targeting = other.targeting;
  }

  /**
   * Creates a DRAFT campaign, or throws {@link CampaignValidationException} with every violation.
   */
  public static Campaign draft(UUID id, UUID advertiserId, NewCampaign input) {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(advertiserId, "advertiserId");
    Objects.requireNonNull(input, "input");
    List<Violation> violations = CampaignValidator.validate(input);
    if (!violations.isEmpty()) {
      throw new CampaignValidationException(violations);
    }
    return new Campaign(id, advertiserId, input, CampaignStatus.DRAFT);
  }

  /**
   * DRAFT to ACTIVE. Needs at least one valid creative and an end date that is still in the future.
   * Creatives are not part of this aggregate yet, so the caller passes how many valid ones exist.
   */
  public Campaign activate(Instant now, int activeCreativeCount) {
    requireStatus("activate", CampaignStatus.DRAFT);
    if (activeCreativeCount < 1) {
      throw new InvalidStateException(
          status, "activate", "at least one valid creative is required");
    }
    if (!endAt.isAfter(now)) {
      throw new InvalidStateException(status, "activate", "endAt is not in the future");
    }
    return new Campaign(this, CampaignStatus.ACTIVE);
  }

  /** ACTIVE to PAUSED; pausing a paused campaign is a no-op. */
  public Campaign pause() {
    if (status == CampaignStatus.PAUSED) {
      return this;
    }
    requireStatus("pause", CampaignStatus.ACTIVE);
    return new Campaign(this, CampaignStatus.PAUSED);
  }

  /** PAUSED to ACTIVE; resuming an active campaign is a no-op. */
  public Campaign resume() {
    if (status == CampaignStatus.ACTIVE) {
      return this;
    }
    requireStatus("resume", CampaignStatus.PAUSED);
    return new Campaign(this, CampaignStatus.ACTIVE);
  }

  /** Soft delete from DRAFT, PAUSED or COMPLETED. An ACTIVE campaign must be paused first. */
  public Campaign archive() {
    requireStatus("archive", CampaignStatus.DRAFT, CampaignStatus.PAUSED, CampaignStatus.COMPLETED);
    return new Campaign(this, CampaignStatus.ARCHIVED);
  }

  /**
   * ACTIVE to COMPLETED; done by the system when the budget is spent or the end date has passed.
   */
  public Campaign complete() {
    requireStatus("complete", CampaignStatus.ACTIVE);
    return new Campaign(this, CampaignStatus.COMPLETED);
  }

  private void requireStatus(String action, CampaignStatus... allowed) {
    for (CampaignStatus candidate : allowed) {
      if (status == candidate) {
        return;
      }
    }
    throw new InvalidStateException(status, action, "allowed only from " + List.of(allowed));
  }

  public UUID id() {
    return id;
  }

  public UUID advertiserId() {
    return advertiserId;
  }

  public String name() {
    return name;
  }

  public CampaignStatus status() {
    return status;
  }

  public Money dailyBudget() {
    return dailyBudget;
  }

  public Money totalBudget() {
    return totalBudget;
  }

  public Money maxBidCpm() {
    return maxBidCpm;
  }

  public BidStrategy bidStrategy() {
    return bidStrategy;
  }

  public PacingMode pacingMode() {
    return pacingMode;
  }

  public Integer freqCapCount() {
    return freqCapCount;
  }

  public Instant startAt() {
    return startAt;
  }

  public Instant endAt() {
    return endAt;
  }

  public Targeting targeting() {
    return targeting;
  }
}
