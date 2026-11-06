package dev.vy.rtb.campaign.domain;

import static dev.vy.rtb.campaign.domain.CampaignTestData.ADVERTISER_ID;
import static dev.vy.rtb.campaign.domain.CampaignTestData.END;
import static dev.vy.rtb.campaign.domain.CampaignTestData.ID;
import static dev.vy.rtb.campaign.domain.CampaignTestData.START;
import static dev.vy.rtb.campaign.domain.CampaignTestData.validInput;
import static org.assertj.core.api.Assertions.assertThat;

import dev.vy.rtb.common.Money;
import org.junit.jupiter.api.Test;

/** Seam: Campaign.draft(...) and the read accessors of Campaign. */
class CampaignDraftTest {

  @Test
  void aValidInputBecomesADraftCampaignKeepingEveryField() {
    Campaign campaign = Campaign.draft(ID, ADVERTISER_ID, validInput().build());

    assertThat(campaign.id()).isEqualTo(ID);
    assertThat(campaign.advertiserId()).isEqualTo(ADVERTISER_ID);
    assertThat(campaign.name()).isEqualTo("Thể thao tháng 11");
    assertThat(campaign.status()).isEqualTo(CampaignStatus.DRAFT);
    assertThat(campaign.dailyBudget()).isEqualTo(Money.ofMicros(100_000_000));
    assertThat(campaign.totalBudget()).isEqualTo(Money.ofMicros(1_500_000_000));
    assertThat(campaign.maxBidCpm()).isEqualTo(Money.ofMicros(2_000_000));
    assertThat(campaign.bidStrategy()).isEqualTo(BidStrategy.fixedCpm(850_000));
    assertThat(campaign.pacingMode()).isEqualTo(PacingMode.EVEN);
    assertThat(campaign.freqCapCount()).isEqualTo(3);
    assertThat(campaign.startAt()).isEqualTo(START);
    assertThat(campaign.endAt()).isEqualTo(END);
  }
}
