package dev.vy.rtb.campaign.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vy.rtb.campaign.domain.BidStrategy;
import dev.vy.rtb.campaign.domain.Campaign;
import dev.vy.rtb.campaign.domain.Creative;
import dev.vy.rtb.campaign.domain.NewCampaign;
import dev.vy.rtb.campaign.domain.NewCreative;
import dev.vy.rtb.campaign.domain.PacingMode;
import dev.vy.rtb.campaign.persistence.Stored;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Seam: what the bidder will read. The event is a contract, so its shape is pinned here. */
class CampaignEventFactoryTest {

  private static final UUID ADVERTISER = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  private static final UUID CAMPAIGN = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final Instant T = Instant.parse("2026-11-09T10:00:00Z");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static Campaign draft() {
    return Campaign.draft(
        CAMPAIGN,
        ADVERTISER,
        NewCampaign.builder()
            .name("Sports")
            .dailyBudgetMicros(100_000_000)
            .totalBudgetMicros(1_500_000_000)
            .maxBidCpmMicros(2_000_000)
            .bidStrategy(BidStrategy.fixedCpm(850_000))
            .pacingMode(PacingMode.EVEN)
            .freqCapCount(3)
            .startAt(Instant.parse("2026-11-01T00:00:00Z"))
            .endAt(Instant.parse("2026-12-10T00:00:00Z"))
            .build());
  }

  private static Stored<Campaign> stored(Campaign campaign) {
    return new Stored<>(campaign, 7, T, T);
  }

  private static Stored<Creative> creative(int n, boolean active, boolean archived) {
    Creative c =
        Creative.create(
            UUID.fromString(String.format("00000000-0000-0000-0000-%012d", n)),
            CAMPAIGN,
            ADVERTISER,
            NewCreative.builder()
                .name("Banner " + n)
                .width(300)
                .height(250)
                .markup("<a href=\"${CLICK_URL}\">x</a>")
                .landingUrl("https://example.com/" + n)
                .build());
    if (archived) {
      c = c.archive();
    } else if (!active) {
      c = c.pause();
    }
    return new Stored<>(c, 2, T, T);
  }

  private static JsonNode json(CampaignEvent event) {
    return JSON.readTree(JSON.writeValueAsString(event));
  }

  @Test
  void aCampaignBecomesOneSelfContainedEvent() {
    Optional<CampaignEvent> event =
        CampaignEventFactory.build(stored(draft()), List.of(creative(1, true, false)), T);

    JsonNode root = json(event.orElseThrow());
    assertThat(root.get("schemaVersion").asInt()).isEqualTo(1);
    assertThat(root.get("emittedAt").asText()).isEqualTo("2026-11-09T10:00:00Z");
    JsonNode campaign = root.get("campaign");
    assertThat(campaign.get("id").asText()).isEqualTo(CAMPAIGN.toString());
    assertThat(campaign.get("advertiserId").asText()).isEqualTo(ADVERTISER.toString());
    assertThat(campaign.get("status").asText()).isEqualTo("DRAFT");
    assertThat(campaign.get("version").asLong()).isEqualTo(7);
    assertThat(campaign.get("dailyBudgetMicros").asLong()).isEqualTo(100_000_000);
    assertThat(campaign.get("maxBidCpmMicros").asLong()).isEqualTo(2_000_000);
    assertThat(campaign.get("bidStrategy").get("type").asText()).isEqualTo("FIXED_CPM");
    assertThat(campaign.get("bidStrategy").get("cpmMicros").asLong()).isEqualTo(850_000);
    assertThat(campaign.get("freqCapCount").asInt()).isEqualTo(3);
    assertThat(campaign.get("startAt").asText()).isEqualTo("2026-11-01T00:00:00Z");
    assertThat(campaign.get("targeting").get("scheduleTimezone").asText())
        .isEqualTo("Asia/Ho_Chi_Minh");
    assertThat(campaign.get("targeting").get("scheduleMask").asText()).hasSize(168);
    JsonNode creatives = root.get("creatives");
    assertThat(creatives).hasSize(1);
    assertThat(creatives.get(0).get("width").asInt()).isEqualTo(300);
    assertThat(creatives.get(0).get("landingUrl").asText()).isEqualTo("https://example.com/1");
  }

  @Test
  void onlyActiveCreativesTravelToTheBidder() {
    CampaignEvent event =
        CampaignEventFactory.build(
                stored(draft()),
                List.of(
                    creative(1, true, false), creative(2, false, false), creative(3, false, true)),
                T)
            .orElseThrow();

    assertThat(event.creatives())
        .extracting(CampaignEvent.CreativeView::name)
        .containsExactly("Banner 1");
  }

  @Test
  void aPausedCampaignStillGetsAFullEventSoTheBidderStopsBidding() {
    Campaign paused = draft().activate(Instant.parse("2026-11-09T09:00:00Z"), 1).pause();

    CampaignEvent event = CampaignEventFactory.build(stored(paused), List.of(), T).orElseThrow();

    assertThat(event.campaign().status()).isEqualTo("PAUSED");
  }

  @Test
  void anArchivedCampaignIsATombstone() {
    Campaign archived = draft().archive();

    assertThat(CampaignEventFactory.build(stored(archived), List.of(), T)).isEmpty();
  }
}
