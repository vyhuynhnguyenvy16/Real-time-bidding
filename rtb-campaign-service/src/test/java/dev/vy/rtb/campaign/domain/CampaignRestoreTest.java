package dev.vy.rtb.campaign.domain;

import static dev.vy.rtb.campaign.domain.CampaignTestData.ADVERTISER_ID;
import static dev.vy.rtb.campaign.domain.CampaignTestData.END;
import static dev.vy.rtb.campaign.domain.CampaignTestData.ID;
import static dev.vy.rtb.campaign.domain.CampaignTestData.START;
import static dev.vy.rtb.campaign.domain.CampaignTestData.validInput;
import static org.assertj.core.api.Assertions.assertThat;

import dev.vy.rtb.common.Money;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Seam: Campaign.restore(...) for the storage layer, and value equality of Campaign. */
class CampaignRestoreTest {

  private static Campaign restoredAs(CampaignStatus status) {
    Campaign source = Campaign.draft(ID, ADVERTISER_ID, validInput().build());
    return Campaign.restore(
        source.id(),
        source.advertiserId(),
        source.name(),
        status,
        source.dailyBudget(),
        source.totalBudget(),
        source.maxBidCpm(),
        source.bidStrategy(),
        source.pacingMode(),
        source.freqCapCount(),
        source.startAt(),
        source.endAt(),
        source.targeting());
  }

  @Test
  void restoreKeepsEveryFieldAndTheGivenStatus() {
    Campaign restored = restoredAs(CampaignStatus.PAUSED);

    assertThat(restored.id()).isEqualTo(ID);
    assertThat(restored.advertiserId()).isEqualTo(ADVERTISER_ID);
    assertThat(restored.name()).isEqualTo("Thể thao tháng 11");
    assertThat(restored.status()).isEqualTo(CampaignStatus.PAUSED);
    assertThat(restored.dailyBudget()).isEqualTo(Money.ofMicros(100_000_000));
    assertThat(restored.startAt()).isEqualTo(START);
    assertThat(restored.endAt()).isEqualTo(END);
  }

  @Test
  void aRestoredCampaignKeepsWorkingInTheStateMachine() {
    assertThat(restoredAs(CampaignStatus.ACTIVE).pause().status()).isEqualTo(CampaignStatus.PAUSED);
    assertThat(restoredAs(CampaignStatus.PAUSED).resume().status())
        .isEqualTo(CampaignStatus.ACTIVE);
    assertThat(restoredAs(CampaignStatus.COMPLETED).archive().status())
        .isEqualTo(CampaignStatus.ARCHIVED);
  }

  @Test
  void campaignsWithTheSameFieldsAreEqual() {
    Campaign first = Campaign.draft(ID, ADVERTISER_ID, validInput().build());
    Campaign second = Campaign.draft(ID, ADVERTISER_ID, validInput().build());

    assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
  }

  @Test
  void campaignsThatDifferInOneFieldAreNotEqual() {
    Campaign base = Campaign.draft(ID, ADVERTISER_ID, validInput().build());

    assertThat(base)
        .isNotEqualTo(Campaign.draft(ID, ADVERTISER_ID, validInput().name("x").build()));
    assertThat(base).isNotEqualTo(base.activate(Instant.parse("2026-11-09T00:00:00Z"), 1));
  }
}
