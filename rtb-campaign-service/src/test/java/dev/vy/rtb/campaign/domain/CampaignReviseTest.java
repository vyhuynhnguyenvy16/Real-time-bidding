package dev.vy.rtb.campaign.domain;

import static dev.vy.rtb.campaign.domain.CampaignTestData.ADVERTISER_ID;
import static dev.vy.rtb.campaign.domain.CampaignTestData.ID;
import static dev.vy.rtb.campaign.domain.CampaignTestData.validInput;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vy.rtb.common.Money;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Seam: Campaign.revise(...), the domain side of PATCH. */
class CampaignReviseTest {

  private static final Instant NOW = Instant.parse("2026-11-09T10:00:00Z");

  private static Campaign draft() {
    return Campaign.draft(ID, ADVERTISER_ID, validInput().build());
  }

  @Test
  void revisingReplacesTheEditableFieldsAndKeepsIdentityAndStatus() {
    Campaign active = draft().activate(NOW, 1);

    Campaign revised =
        active.revise(validInput().name("Tên mới").dailyBudgetMicros(200_000_000).build());

    assertThat(revised.name()).isEqualTo("Tên mới");
    assertThat(revised.dailyBudget()).isEqualTo(Money.ofMicros(200_000_000));
    assertThat(revised.id()).isEqualTo(ID);
    assertThat(revised.advertiserId()).isEqualTo(ADVERTISER_ID);
    assertThat(revised.status()).isEqualTo(CampaignStatus.ACTIVE);
  }

  @Test
  void aDraftAndAPausedCampaignCanBeRevised() {
    assertThat(draft().revise(validInput().name("a").build()).status())
        .isEqualTo(CampaignStatus.DRAFT);
    assertThat(draft().activate(NOW, 1).pause().revise(validInput().name("b").build()).status())
        .isEqualTo(CampaignStatus.PAUSED);
  }

  @Test
  void revisingValidatesLikeCreating() {
    assertThatThrownBy(() -> draft().revise(validInput().dailyBudgetMicros(0).build()))
        .isInstanceOfSatisfying(
            CampaignValidationException.class,
            e ->
                assertThat(e.violations())
                    .extracting(Violation::field)
                    .contains("dailyBudgetMicros"));
  }

  @Test
  void aFinishedCampaignCannotBeRevised() {
    Campaign completed = draft().activate(NOW, 1).complete();
    Campaign archived = draft().archive();

    assertThatThrownBy(() -> completed.revise(validInput().build()))
        .isInstanceOf(InvalidStateException.class);
    assertThatThrownBy(() -> archived.revise(validInput().build()))
        .isInstanceOf(InvalidStateException.class);
  }

  @Test
  void revisingLeavesTheOriginalUntouched() {
    Campaign original = draft();

    original.revise(validInput().name("changed").build());

    assertThat(original.name()).isEqualTo("Thể thao tháng 11");
  }
}
