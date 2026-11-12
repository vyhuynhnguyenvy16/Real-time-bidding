package dev.vy.rtb.bidder.match;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vy.rtb.bidder.snapshot.BidderCampaign;
import dev.vy.rtb.bidder.snapshot.BidderCreative;
import dev.vy.rtb.common.NoBidReason;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MatcherTest {

  /** Thursday 2026-11-12 03:30 UTC = 10:30 in Asia/Ho_Chi_Minh. */
  private static final Instant NOW = Instant.parse("2026-11-12T03:30:00Z");

  private static final String ALWAYS = "1".repeat(168);

  private static BidderCreative creative(int w, int h) {
    return new BidderCreative(UUID.randomUUID(), w, h, "<a>ad</a>", "https://example.com/x");
  }

  private static BidderCampaign.Builder campaign() {
    return BidderCampaign.builder()
        .id(UUID.randomUUID())
        .maxBidCpmMicros(2_000_000)
        .fixedCpmMicros(1_500_000L)
        .window(Instant.parse("2026-11-01T00:00:00Z"), Instant.parse("2026-12-01T00:00:00Z"))
        .scheduleMask(ALWAYS)
        .creatives(List.of(creative(300, 250)));
  }

  private static BidRequest.Builder request() {
    return BidRequest.builder().id("req-1").size(300, 250).country("VN").deviceType(2);
  }

  private static Decision decide(List<BidderCampaign> campaigns, BidRequest request) {
    return Matcher.decide(campaigns, request, NOW);
  }

  @Test
  void bidsAtTheCampaignPriceWhenEverythingMatches() {
    BidderCampaign c = campaign().build();

    Decision decision = decide(List.of(c), request().build());

    assertThat(decision).isInstanceOf(Decision.Bid.class);
    Decision.Bid bid = (Decision.Bid) decision;
    assertThat(bid.campaignId()).isEqualTo(c.id());
    assertThat(bid.priceCpmMicros()).isEqualTo(1_500_000);
    assertThat(bid.creativeId()).isEqualTo(c.creatives().get(0).id());
  }

  @Test
  void neverPaysMoreThanTheMaxBid() {
    BidderCampaign c = campaign().maxBidCpmMicros(1_000_000).fixedCpmMicros(1_500_000L).build();

    Decision.Bid bid = (Decision.Bid) decide(List.of(c), request().build());

    assertThat(bid.priceCpmMicros()).isEqualTo(1_000_000);
  }

  @Test
  void noCampaignsIsNoCandidate() {
    assertThat(decide(List.of(), request().build()))
        .isEqualTo(new Decision.NoBid(NoBidReason.NO_CANDIDATE));
  }

  @Test
  void aCreativeOfAnotherSizeIsNoCandidate() {
    BidderCampaign c = campaign().creatives(List.of(creative(728, 90))).build();

    assertThat(decide(List.of(c), request().build()))
        .isEqualTo(new Decision.NoBid(NoBidReason.NO_CANDIDATE));
  }

  @Test
  void outsideTheDateWindowIsNoCandidate() {
    BidderCampaign notYet =
        campaign()
            .window(Instant.parse("2026-11-13T00:00:00Z"), Instant.parse("2026-12-01T00:00:00Z"))
            .build();
    BidderCampaign ended =
        campaign()
            .window(Instant.parse("2026-11-01T00:00:00Z"), Instant.parse("2026-11-12T03:30:00Z"))
            .build();

    assertThat(decide(List.of(notYet, ended), request().build()))
        .isEqualTo(new Decision.NoBid(NoBidReason.NO_CANDIDATE));
  }

  @Test
  void targetCpcCampaignsDoNotBidYet() {
    BidderCampaign c = campaign().fixedCpmMicros(null).build();

    assertThat(decide(List.of(c), request().build()))
        .isEqualTo(new Decision.NoBid(NoBidReason.NO_CANDIDATE));
  }

  @Test
  void countryMustBeInTheList() {
    BidderCampaign c = campaign().countries(Set.of("SG")).build();

    assertThat(decide(List.of(c), request().build()))
        .isEqualTo(new Decision.NoBid(NoBidReason.GEO_MISMATCH));
    assertThat(decide(List.of(c), request().country(null).build()))
        .isEqualTo(new Decision.NoBid(NoBidReason.GEO_MISMATCH));
    assertThat(decide(List.of(c), request().country("SG").build()))
        .isInstanceOf(Decision.Bid.class);
  }

  @Test
  void emptyTargetingListsMeanNoRestriction() {
    BidderCampaign c = campaign().build();

    assertThat(decide(List.of(c), request().country(null).deviceType(null).build()))
        .isInstanceOf(Decision.Bid.class);
  }

  @Test
  void deviceTypeMustBeInTheList() {
    BidderCampaign c = campaign().deviceTypes(Set.of(4)).build();

    assertThat(decide(List.of(c), request().build()))
        .isEqualTo(new Decision.NoBid(NoBidReason.DEVICE_MISMATCH));
  }

  @Test
  void oneSharedCategoryIsEnough() {
    BidderCampaign c = campaign().categories(Set.of("iab1", "iab2")).build();

    assertThat(decide(List.of(c), request().categories(Set.of("iab9")).build()))
        .isEqualTo(new Decision.NoBid(NoBidReason.CATEGORY_MISMATCH));
    assertThat(decide(List.of(c), request().categories(Set.of("iab9", "iab2")).build()))
        .isInstanceOf(Decision.Bid.class);
  }

  @Test
  void blockedPublisherIsRefused() {
    BidderCampaign c = campaign().publisherBlocklist(Set.of("pub-bad")).build();

    assertThat(decide(List.of(c), request().publisherId("pub-bad").build()))
        .isEqualTo(new Decision.NoBid(NoBidReason.BLOCKED_PUBLISHER));
    assertThat(decide(List.of(c), request().publisherId("pub-ok").build()))
        .isInstanceOf(Decision.Bid.class);
  }

  @Test
  void scheduleIsReadInVietnamTime() {
    // Thursday = day 4, hour 10 -> index (4 - 1) * 24 + 10 = 82.
    StringBuilder only = new StringBuilder("0".repeat(168));
    only.setCharAt(82, '1');
    StringBuilder other = new StringBuilder("0".repeat(168));
    other.setCharAt(81, '1');

    assertThat(decide(List.of(campaign().scheduleMask(only.toString()).build()), request().build()))
        .isInstanceOf(Decision.Bid.class);
    assertThat(
            decide(List.of(campaign().scheduleMask(other.toString()).build()), request().build()))
        .isEqualTo(new Decision.NoBid(NoBidReason.OUT_OF_SCHEDULE));
  }

  @Test
  void priceBelowTheFloorIsRefused() {
    BidderCampaign c = campaign().build();

    assertThat(decide(List.of(c), request().floorCpmMicros(1_500_001).build()))
        .isEqualTo(new Decision.NoBid(NoBidReason.BELOW_FLOOR));
    assertThat(decide(List.of(c), request().floorCpmMicros(1_500_000).build()))
        .isInstanceOf(Decision.Bid.class);
  }

  @Test
  void theReasonComesFromTheStageThatEliminatedTheLastCandidate() {
    BidderCampaign geo = campaign().countries(Set.of("SG")).build();
    BidderCampaign device = campaign().deviceTypes(Set.of(4)).build();

    // geo fails first for `geo`; `device` survives geo and fails on device: the later stage wins.
    assertThat(decide(List.of(geo, device), request().build()))
        .isEqualTo(new Decision.NoBid(NoBidReason.DEVICE_MISMATCH));
  }

  @Test
  void theHighestPriceWins() {
    BidderCampaign cheap = campaign().maxBidCpmMicros(5_000_000).fixedCpmMicros(1_000_000L).build();
    BidderCampaign dear = campaign().maxBidCpmMicros(5_000_000).fixedCpmMicros(3_000_000L).build();

    Decision.Bid bid = (Decision.Bid) decide(List.of(cheap, dear), request().build());

    assertThat(bid.campaignId()).isEqualTo(dear.id());
  }

  @Test
  void aTieIsBrokenTheSameWayEveryTime() {
    BidderCampaign a = campaign().build();
    BidderCampaign b = campaign().build();

    Decision first = decide(List.of(a, b), request().build());
    Decision second = decide(List.of(b, a), request().build());

    assertThat(first).isEqualTo(second);
  }

  @Test
  void tiesSpreadAcrossRequests() {
    BidderCampaign a = campaign().build();
    BidderCampaign b = campaign().build();

    Set<UUID> winners = new java.util.HashSet<>();
    for (int i = 0; i < 50; i++) {
      winners.add(
          ((Decision.Bid) decide(List.of(a, b), request().id("req-" + i).build())).campaignId());
    }

    assertThat(winners).containsExactlyInAnyOrder(a.id(), b.id());
  }
}
