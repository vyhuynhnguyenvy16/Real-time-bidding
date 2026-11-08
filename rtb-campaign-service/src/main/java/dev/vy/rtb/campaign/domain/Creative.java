package dev.vy.rtb.campaign.domain;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** An ad creative (banner) of one campaign. Immutable; every change returns a new instance. */
public final class Creative {

  private final UUID id;
  private final UUID campaignId;
  private final UUID advertiserId;
  private final String name;
  private final int width;
  private final int height;
  private final String markup;
  private final String landingUrl;
  private final CreativeStatus status;

  private Creative(
      UUID id, UUID campaignId, UUID advertiserId, NewCreative in, CreativeStatus status) {
    this.id = id;
    this.campaignId = campaignId;
    this.advertiserId = advertiserId;
    this.name = in.name().strip();
    this.width = in.width();
    this.height = in.height();
    this.markup = in.markup();
    this.landingUrl = in.landingUrl();
    this.status = status;
  }

  /** A new creative is ACTIVE. Throws {@link CampaignValidationException} listing every problem. */
  public static Creative create(UUID id, UUID campaignId, UUID advertiserId, NewCreative input) {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(campaignId, "campaignId");
    Objects.requireNonNull(advertiserId, "advertiserId");
    return build(id, campaignId, advertiserId, input, CreativeStatus.ACTIVE);
  }

  /** Rebuilds a stored creative; the data was validated when it was written. */
  public static Creative restore(
      UUID id, UUID campaignId, UUID advertiserId, NewCreative stored, CreativeStatus status) {
    return new Creative(id, campaignId, advertiserId, stored, status);
  }

  private static Creative build(
      UUID id, UUID campaignId, UUID advertiserId, NewCreative input, CreativeStatus status) {
    Objects.requireNonNull(input, "input");
    List<Violation> violations = CreativeValidator.validate(input);
    if (!violations.isEmpty()) {
      throw new CampaignValidationException(violations);
    }
    return new Creative(id, campaignId, advertiserId, input, status);
  }

  /** Replaces the editable content; keeps id, campaign, advertiser and status. */
  public Creative revise(NewCreative input) {
    if (status == CreativeStatus.ARCHIVED) {
      throw new CreativeStateException(status, "revise");
    }
    return build(id, campaignId, advertiserId, input, status);
  }

  /** ACTIVE to PAUSED; pausing a paused creative is a no-op. */
  public Creative pause() {
    if (status == CreativeStatus.PAUSED) {
      return this;
    }
    requireNotArchived("pause");
    return withStatus(CreativeStatus.PAUSED);
  }

  /** PAUSED to ACTIVE; resuming an active creative is a no-op. */
  public Creative resume() {
    if (status == CreativeStatus.ACTIVE) {
      return this;
    }
    requireNotArchived("resume");
    return withStatus(CreativeStatus.ACTIVE);
  }

  /** Any status to ARCHIVED, which is final; archiving an archived creative is a no-op. */
  public Creative archive() {
    return status == CreativeStatus.ARCHIVED ? this : withStatus(CreativeStatus.ARCHIVED);
  }

  private void requireNotArchived(String action) {
    if (status == CreativeStatus.ARCHIVED) {
      throw new CreativeStateException(status, action);
    }
  }

  private Creative withStatus(CreativeStatus next) {
    return new Creative(id, campaignId, advertiserId, asInput(), next);
  }

  private NewCreative asInput() {
    return new NewCreative(name, width, height, markup, landingUrl);
  }

  public UUID id() {
    return id;
  }

  public UUID campaignId() {
    return campaignId;
  }

  public UUID advertiserId() {
    return advertiserId;
  }

  public String name() {
    return name;
  }

  public int width() {
    return width;
  }

  public int height() {
    return height;
  }

  public String markup() {
    return markup;
  }

  public String landingUrl() {
    return landingUrl;
  }

  public CreativeStatus status() {
    return status;
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof Creative c
        && id.equals(c.id)
        && campaignId.equals(c.campaignId)
        && advertiserId.equals(c.advertiserId)
        && name.equals(c.name)
        && width == c.width
        && height == c.height
        && markup.equals(c.markup)
        && landingUrl.equals(c.landingUrl)
        && status == c.status;
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        id, campaignId, advertiserId, name, width, height, markup, landingUrl, status);
  }

  @Override
  public String toString() {
    return "Creative[" + id + ", " + name + ", " + width + "x" + height + ", " + status + "]";
  }
}
