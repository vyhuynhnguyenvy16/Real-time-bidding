package dev.vy.rtb.bidder.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SnapshotStoreTest {

  private static final UUID ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final String MASK = "1".repeat(168);

  /** The same shape campaign-service writes (docs/event-schema-campaign-updates.md). */
  private static String event(UUID id, long revision, String status, String creatives) {
    return """
    {"schemaVersion":1,"revision":%d,"emittedAt":"2026-11-12T03:00:00Z",
     "campaign":{"id":"%s","advertiserId":"22222222-2222-2222-2222-222222222222",
       "name":"n","status":"%s","dailyBudgetMicros":10000000,"totalBudgetMicros":50000000,
       "maxBidCpmMicros":2000000,
       "bidStrategy":{"type":"FIXED_CPM","cpmMicros":1500000},
       "pacingMode":"EVEN","freqCapCount":3,
       "startAt":"2026-11-01T00:00:00Z","endAt":"2026-12-01T00:00:00Z","version":4,
       "targeting":{"countries":["VN"],"deviceTypes":[2],"categories":["iab1"],
         "publisherBlocklist":["bad"],"scheduleTimezone":"Asia/Ho_Chi_Minh",
         "scheduleMask":"%s"},"unknownFutureField":true},
     "creatives":%s}
    """
        .formatted(revision, id, status, MASK, creatives);
  }

  private static final String ONE_CREATIVE =
      """
      [{"id":"33333333-3333-3333-3333-333333333333","name":"c","width":300,"height":250,
        "markup":"<a>x</a>","landingUrl":"https://example.com","version":1}]
      """;

  private static SnapshotStore.Change change(UUID id, String json) {
    return new SnapshotStore.Change(id.toString(), json);
  }

  @Test
  void startsEmpty() {
    assertThat(new SnapshotStore().campaigns()).isEmpty();
  }

  @Test
  void anActiveCampaignBecomesBiddable() {
    SnapshotStore store = new SnapshotStore();

    store.apply(List.of(change(ID, event(ID, 10, "ACTIVE", ONE_CREATIVE))));

    assertThat(store.campaigns()).hasSize(1);
    BidderCampaign c = store.campaigns().get(0);
    assertThat(c.id()).isEqualTo(ID);
    assertThat(c.maxBidCpmMicros()).isEqualTo(2_000_000);
    assertThat(c.fixedCpmMicros()).isEqualTo(1_500_000L);
    assertThat(c.countries()).containsExactly("VN");
    assertThat(c.deviceTypes()).containsExactly(2);
    assertThat(c.categories()).containsExactly("iab1");
    assertThat(c.publisherBlocklist()).containsExactly("bad");
    assertThat(c.scheduleMask()).isEqualTo(MASK);
    assertThat(c.creatives()).hasSize(1);
    assertThat(c.creatives().get(0).width()).isEqualTo(300);
  }

  @Test
  void pausingRemovesTheCampaign() {
    SnapshotStore store = new SnapshotStore();
    store.apply(List.of(change(ID, event(ID, 10, "ACTIVE", ONE_CREATIVE))));

    store.apply(List.of(change(ID, event(ID, 11, "PAUSED", "[]"))));

    assertThat(store.campaigns()).isEmpty();
  }

  @Test
  void aTombstoneRemovesTheCampaign() {
    SnapshotStore store = new SnapshotStore();
    store.apply(List.of(change(ID, event(ID, 10, "ACTIVE", ONE_CREATIVE))));

    store.apply(List.of(change(ID, null)));

    assertThat(store.campaigns()).isEmpty();
  }

  @Test
  void anOlderOrRepeatedRevisionIsIgnored() {
    SnapshotStore store = new SnapshotStore();
    store.apply(List.of(change(ID, event(ID, 10, "PAUSED", "[]"))));

    store.apply(List.of(change(ID, event(ID, 9, "ACTIVE", ONE_CREATIVE))));
    assertThat(store.campaigns()).isEmpty();

    store.apply(List.of(change(ID, event(ID, 10, "ACTIVE", ONE_CREATIVE))));
    assertThat(store.campaigns()).isEmpty();

    store.apply(List.of(change(ID, event(ID, 11, "ACTIVE", ONE_CREATIVE))));
    assertThat(store.campaigns()).hasSize(1);
  }

  @Test
  void aCampaignWithNoActiveCreativeIsNotBiddable() {
    SnapshotStore store = new SnapshotStore();

    store.apply(List.of(change(ID, event(ID, 10, "ACTIVE", "[]"))));

    assertThat(store.campaigns()).isEmpty();
  }

  @Test
  void garbageAndUnknownSchemaAreSkippedWithoutHurtingTheRest() {
    SnapshotStore store = new SnapshotStore();
    UUID other = UUID.fromString("44444444-4444-4444-4444-444444444444");

    store.apply(
        List.of(
            change(ID, "{not json"),
            change(
                ID,
                event(ID, 10, "ACTIVE", ONE_CREATIVE)
                    .replace("\"schemaVersion\":1", "\"schemaVersion\":2")),
            change(other, event(other, 5, "ACTIVE", ONE_CREATIVE))));

    assertThat(store.campaigns()).extracting(BidderCampaign::id).containsExactly(other);
    assertThat(store.rejected()).isEqualTo(2);
  }

  @Test
  void aBatchSwapsOnceAndAReaderKeepsItsOldView() {
    SnapshotStore store = new SnapshotStore();
    store.apply(List.of(change(ID, event(ID, 10, "ACTIVE", ONE_CREATIVE))));
    List<BidderCampaign> before = store.campaigns();

    store.apply(List.of(change(ID, null)));

    assertThat(before).hasSize(1);
    assertThat(store.campaigns()).isEmpty();
  }

  @Test
  void aTargetCpcCampaignKeepsANullFixedPrice() {
    SnapshotStore store = new SnapshotStore();
    String json =
        event(ID, 10, "ACTIVE", ONE_CREATIVE)
            .replace(
                "\"type\":\"FIXED_CPM\",\"cpmMicros\":1500000",
                "\"type\":\"TARGET_CPC\",\"cpcMicros\":900000");

    store.apply(List.of(change(ID, json)));

    assertThat(store.campaigns().get(0).fixedCpmMicros()).isNull();
  }
}
