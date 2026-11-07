package dev.vy.rtb.campaign.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The valid example of docs/api-contract.md section 3.2; tests change one field at a time. */
public final class CampaignTestData {

  public static final UUID ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  public static final UUID ADVERTISER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
  public static final Instant START = Instant.parse("2026-11-10T00:00:00Z");
  public static final Instant END = Instant.parse("2026-12-10T00:00:00Z");

  private CampaignTestData() {}

  public static NewCampaign.Builder validInput() {
    return NewCampaign.builder()
        .name("Thể thao tháng 11")
        .dailyBudgetMicros(100_000_000)
        .totalBudgetMicros(1_500_000_000)
        .maxBidCpmMicros(2_000_000)
        .bidStrategy(BidStrategy.fixedCpm(850_000))
        .pacingMode(PacingMode.EVEN)
        .freqCapCount(3)
        .startAt(START)
        .endAt(END)
        .targeting(
            new TargetingInput(
                List.of("VNM"),
                List.of(1, 4, 5),
                List.of("IAB17"),
                List.of("spam.example"),
                Map.of("all", List.of(18, 19, 20, 21, 22))));
  }
}
