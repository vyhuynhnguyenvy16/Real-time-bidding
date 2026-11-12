package dev.vy.rtb.bidder.snapshot;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * The bidder's in-memory view of the ACTIVE campaigns.
 *
 * <p>Readers call {@link #campaigns()} and get an immutable list; a request keeps that list until
 * it is done, so a change never shows up half way through a request. Writers build the next list
 * and swap it in with one volatile write. Events are at-least-once, so an event whose {@code
 * revision} is not newer than the last one seen for that campaign is ignored.
 */
public final class SnapshotStore {

  private static final Logger LOG = LoggerFactory.getLogger(SnapshotStore.class);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** One Kafka record: key = campaign id, value = event JSON or null for a tombstone. */
  public record Change(String key, String value) {}

  private volatile List<BidderCampaign> view = List.of();
  private final Map<UUID, BidderCampaign> active = new HashMap<>();
  private final Map<UUID, Long> revisions = new HashMap<>();
  private final AtomicLong rejected = new AtomicLong();

  public List<BidderCampaign> campaigns() {
    return view;
  }

  /** How many records were skipped because they could not be read or had an unknown schema. */
  public long rejected() {
    return rejected.get();
  }

  /** Applies a batch and swaps the view once at the end. */
  public synchronized void apply(List<Change> changes) {
    for (Change change : changes) {
      try {
        applyOne(change);
      } catch (RuntimeException e) {
        rejected.incrementAndGet();
        LOG.warn("Skipping unreadable campaign event for key {}: {}", change.key(), e.toString());
      }
    }
    view = List.copyOf(new ArrayList<>(active.values()));
  }

  private void applyOne(Change change) {
    if (change.value() == null) {
      UUID id = UUID.fromString(change.key());
      active.remove(id);
      revisions.remove(id);
      return;
    }
    CampaignEventDto event = JSON.readValue(change.value(), CampaignEventDto.class);
    if (event.schemaVersion() != CampaignEventDto.SUPPORTED_SCHEMA) {
      throw new IllegalArgumentException("unsupported schemaVersion " + event.schemaVersion());
    }
    CampaignEventDto.CampaignDto c = event.campaign();
    Long seen = revisions.get(c.id());
    if (seen != null && event.revision() <= seen) {
      return;
    }
    revisions.put(c.id(), event.revision());
    if (!"ACTIVE".equals(c.status()) || event.creatives().isEmpty()) {
      active.remove(c.id());
      return;
    }
    active.put(c.id(), toCampaign(c, event.creatives()));
  }

  private static BidderCampaign toCampaign(
      CampaignEventDto.CampaignDto c, List<CampaignEventDto.CreativeDto> creatives) {
    CampaignEventDto.TargetingDto t = c.targeting();
    boolean fixed = "FIXED_CPM".equals(c.bidStrategy().type());
    return BidderCampaign.builder()
        .id(c.id())
        .maxBidCpmMicros(c.maxBidCpmMicros())
        .fixedCpmMicros(fixed ? c.bidStrategy().cpmMicros() : null)
        .window(Instant.parse(c.startAt()), Instant.parse(c.endAt()))
        .countries(Set.copyOf(t.countries()))
        .deviceTypes(Set.copyOf(t.deviceTypes()))
        .categories(Set.copyOf(t.categories()))
        .publisherBlocklist(Set.copyOf(t.publisherBlocklist()))
        .scheduleMask(t.scheduleMask())
        .creatives(
            creatives.stream()
                .map(
                    cr ->
                        new BidderCreative(
                            cr.id(), cr.width(), cr.height(), cr.markup(), cr.landingUrl()))
                .toList())
        .build();
  }
}
