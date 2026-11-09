package dev.vy.rtb.campaign.outbox;

import dev.vy.rtb.campaign.domain.Campaign;
import dev.vy.rtb.campaign.domain.Creative;
import dev.vy.rtb.campaign.persistence.CampaignRepository;
import dev.vy.rtb.campaign.persistence.CreativeRepository;
import dev.vy.rtb.campaign.persistence.Stored;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes the outbox row for a campaign from what is in the database right now. It reads through the
 * plain repositories (never the recording ones), so recording cannot recurse. Call it inside the
 * transaction of the change it describes.
 */
public final class OutboxRecorder {

  private final CampaignRepository campaigns;
  private final CreativeRepository creatives;
  private final OutboxStore store;
  private final JsonMapper json;
  private final Clock clock;

  public OutboxRecorder(
      CampaignRepository campaigns,
      CreativeRepository creatives,
      OutboxStore store,
      JsonMapper json,
      Clock clock) {
    this.campaigns = campaigns;
    this.creatives = creatives;
    this.store = store;
    this.json = json;
    this.clock = clock;
  }

  /** Records the campaign's current state: a full event, or a tombstone once it is archived. */
  public void record(UUID advertiserId, UUID campaignId) {
    Stored<Campaign> campaign =
        campaigns
            .findById(advertiserId, campaignId)
            .orElseThrow(
                () -> new IllegalStateException("Campaign " + campaignId + " vanished mid-write"));
    List<Stored<Creative>> all = creatives.listByCampaign(advertiserId, campaignId);
    Optional<CampaignEvent> event = CampaignEventFactory.build(campaign, all, clock.instant());
    store.append(campaignId, event.map(json::writeValueAsString).orElse(null));
  }
}
