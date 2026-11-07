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

  private Campaign(
      UUID id,
      UUID advertiserId,
      String name,
      CampaignStatus status,
      Money dailyBudget,
      Money totalBudget,
      Money maxBidCpm,
      BidStrategy bidStrategy,
      PacingMode pacingMode,
      Integer freqCapCount,
      Instant startAt,
      Instant endAt,
      Targeting targeting) {
    this.id = id;
    this.advertiserId = advertiserId;
    this.name = name;
    this.status = status;
    this.dailyBudget = dailyBudget;
    this.totalBudget = totalBudget;
    this.maxBidCpm = maxBidCpm;
    this.bidStrategy = bidStrategy;
    this.pacingMode = pacingMode;
    this.freqCapCount = freqCapCount;
    this.startAt = startAt;
    this.endAt = endAt;
    this.targeting = targeting;
  }

  private Campaign withStatus(CampaignStatus newStatus) {
    return new Campaign(
        id,
        advertiserId,
        name,
        newStatus,
        dailyBudget,
        totalBudget,
        maxBidCpm,
        bidStrategy,
        pacingMode,
        freqCapCount,
        startAt,
        endAt,
        targeting);
  }

  /**
   * Rebuilds a campaign that was stored earlier, in any status. For the storage layer only: it does
   * not validate, because the database already holds only rows that passed validation and its own
   * CHECK constraints. Use {@link #draft} to create a new campaign.
   */
  public static Campaign restore(
      UUID id,
      UUID advertiserId,
      String name,
      CampaignStatus status,
      Money dailyBudget,
      Money totalBudget,
      Money maxBidCpm,
      BidStrategy bidStrategy,
      PacingMode pacingMode,
      Integer freqCapCount,
      Instant startAt,
      Instant endAt,
      Targeting targeting) {
    return new Campaign(
        id,
        advertiserId,
        name,
        status,
        dailyBudget,
        totalBudget,
        maxBidCpm,
        bidStrategy,
        pacingMode,
        freqCapCount,
        startAt,
        endAt,
        targeting);
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
    return new Campaign(
        id,
        advertiserId,
        input.name(),
        CampaignStatus.DRAFT,
        Money.ofMicros(input.dailyBudgetMicros()),
        Money.ofMicros(input.totalBudgetMicros()),
        Money.ofMicros(input.maxBidCpmMicros()),
        input.bidStrategy(),
        input.pacingMode(),
        input.freqCapCount(),
        input.startAt(),
        input.endAt(),
        TargetingRules.normalise(input.targeting()));
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
    return withStatus(CampaignStatus.ACTIVE);
  }

  /** ACTIVE to PAUSED; pausing a paused campaign is a no-op. */
  public Campaign pause() {
    if (status == CampaignStatus.PAUSED) {
      return this;
    }
    requireStatus("pause", CampaignStatus.ACTIVE);
    return withStatus(CampaignStatus.PAUSED);
  }

  /** PAUSED to ACTIVE; resuming an active campaign is a no-op. */
  public Campaign resume() {
    if (status == CampaignStatus.ACTIVE) {
      return this;
    }
    requireStatus("resume", CampaignStatus.PAUSED);
    return withStatus(CampaignStatus.ACTIVE);
  }

  /** Soft delete from DRAFT, PAUSED or COMPLETED. An ACTIVE campaign must be paused first. */
  public Campaign archive() {
    requireStatus("archive", CampaignStatus.DRAFT, CampaignStatus.PAUSED, CampaignStatus.COMPLETED);
    return withStatus(CampaignStatus.ARCHIVED);
  }

  /**
   * ACTIVE to COMPLETED; done by the system when the budget is spent or the end date has passed.
   */
  public Campaign complete() {
    requireStatus("complete", CampaignStatus.ACTIVE);
    return withStatus(CampaignStatus.COMPLETED);
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

  @Override
  public boolean equals(Object other) {
    return other instanceof Campaign that
        && id.equals(that.id)
        && advertiserId.equals(that.advertiserId)
        && Objects.equals(name, that.name)
        && status == that.status
        && dailyBudget.equals(that.dailyBudget)
        && totalBudget.equals(that.totalBudget)
        && maxBidCpm.equals(that.maxBidCpm)
        && Objects.equals(bidStrategy, that.bidStrategy)
        && pacingMode == that.pacingMode
        && Objects.equals(freqCapCount, that.freqCapCount)
        && Objects.equals(startAt, that.startAt)
        && Objects.equals(endAt, that.endAt)
        && Objects.equals(targeting, that.targeting);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        id,
        advertiserId,
        name,
        status,
        dailyBudget,
        totalBudget,
        maxBidCpm,
        bidStrategy,
        pacingMode,
        freqCapCount,
        startAt,
        endAt,
        targeting);
  }
}
