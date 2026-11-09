package dev.vy.rtb.campaign.domain;

import static dev.vy.rtb.campaign.domain.CampaignTestData.ADVERTISER_ID;
import static dev.vy.rtb.campaign.domain.CampaignTestData.END;
import static dev.vy.rtb.campaign.domain.CampaignTestData.ID;
import static dev.vy.rtb.campaign.domain.CampaignTestData.START;
import static dev.vy.rtb.campaign.domain.CampaignTestData.validInput;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Seam: the transition methods of Campaign; the state machine is data-model 1.3. */
class CampaignLifecycleTest {

  private static final Instant NOW = Instant.parse("2026-11-09T10:00:00Z"); // before START and END

  private static Campaign draft() {
    return Campaign.draft(ID, ADVERTISER_ID, validInput().build());
  }

  private static Campaign active() {
    return draft().activate(NOW, 1);
  }

  private static Campaign paused() {
    return active().pause();
  }

  private static Campaign completed() {
    return active().complete();
  }

  private static Campaign archived() {
    return draft().archive();
  }

  private static void assertInvalidState(Runnable transition) {
    assertThatThrownBy(transition::run).isInstanceOf(InvalidStateException.class);
  }

  // --- activate -------------------------------------------------------------------------------

  @Test
  void resumingNeedsAnActiveCreativeLikeActivating() {
    assertInvalidState(() -> paused().resume(0));
  }

  @Test
  void activatingADraftWithACreativeMakesItActive() {
    assertThat(draft().activate(NOW, 1).status()).isEqualTo(CampaignStatus.ACTIVE);
  }

  @Test
  void activationNeedsAtLeastOneValidCreative() {
    assertInvalidState(() -> draft().activate(NOW, 0));
  }

  @Test
  void activationIsRefusedWhenTheEndDateIsInThePast() {
    assertInvalidState(() -> draft().activate(END.plusSeconds(1), 1));
  }

  @Test
  void activationIsRefusedExactlyAtTheEndDate() {
    assertInvalidState(() -> draft().activate(END, 1));
  }

  @Test
  void activationIsAllowedBeforeTheStartDate() {
    assertThat(NOW).isBefore(START);
    assertThat(draft().activate(NOW, 1).status()).isEqualTo(CampaignStatus.ACTIVE);
  }

  @Test
  void onlyADraftCanBeActivated() {
    assertInvalidState(() -> active().activate(NOW, 1));
    assertInvalidState(() -> paused().activate(NOW, 1));
    assertInvalidState(() -> completed().activate(NOW, 1));
    assertInvalidState(() -> archived().activate(NOW, 1));
  }

  // --- pause / resume -------------------------------------------------------------------------

  @Test
  void pausingAnActiveCampaignAndResumingItGoesBackToActive() {
    assertThat(active().pause().status()).isEqualTo(CampaignStatus.PAUSED);
    assertThat(paused().resume(1).status()).isEqualTo(CampaignStatus.ACTIVE);
  }

  @Test
  void pauseAndResumeAreIdempotent() {
    assertThat(paused().pause().status()).isEqualTo(CampaignStatus.PAUSED);
    assertThat(active().resume(1).status()).isEqualTo(CampaignStatus.ACTIVE);
  }

  @Test
  void aDraftCannotBePausedOrResumed() {
    assertInvalidState(() -> draft().pause());
    assertInvalidState(() -> draft().resume(1));
  }

  @Test
  void aFinishedCampaignCannotBePausedOrResumed() {
    assertInvalidState(() -> completed().pause());
    assertInvalidState(() -> completed().resume(1));
    assertInvalidState(() -> archived().pause());
    assertInvalidState(() -> archived().resume(1));
  }

  // --- archive --------------------------------------------------------------------------------

  @Test
  void aDraftPausedOrCompletedCampaignCanBeArchived() {
    assertThat(draft().archive().status()).isEqualTo(CampaignStatus.ARCHIVED);
    assertThat(paused().archive().status()).isEqualTo(CampaignStatus.ARCHIVED);
    assertThat(completed().archive().status()).isEqualTo(CampaignStatus.ARCHIVED);
  }

  @Test
  void anActiveCampaignMustBePausedBeforeItIsArchived() {
    assertInvalidState(() -> active().archive());
  }

  @Test
  void anArchivedCampaignCannotBeArchivedAgain() {
    assertInvalidState(() -> archived().archive());
  }

  // --- complete (done by the system, not the advertiser) --------------------------------------

  @Test
  void anActiveCampaignCanBeCompleted() {
    assertThat(active().complete().status()).isEqualTo(CampaignStatus.COMPLETED);
  }

  @Test
  void onlyAnActiveCampaignCanBeCompleted() {
    assertInvalidState(() -> draft().complete());
    assertInvalidState(() -> paused().complete());
    assertInvalidState(() -> completed().complete());
    assertInvalidState(() -> archived().complete());
  }

  // --- immutability and error content ---------------------------------------------------------

  @Test
  void aTransitionLeavesTheOriginalCampaignUntouched() {
    Campaign original = draft();

    Campaign activated = original.activate(NOW, 1);

    assertThat(original.status()).isEqualTo(CampaignStatus.DRAFT);
    assertThat(activated.status()).isEqualTo(CampaignStatus.ACTIVE);
    assertThat(activated.id()).isEqualTo(original.id());
    assertThat(activated.name()).isEqualTo(original.name());
    assertThat(activated.targeting()).isEqualTo(original.targeting());
  }

  @Test
  void theErrorNamesTheCurrentStatusAndTheAttemptedAction() {
    assertThatThrownBy(() -> archived().pause())
        .isInstanceOfSatisfying(
            InvalidStateException.class,
            e -> {
              assertThat(e.currentStatus()).isEqualTo(CampaignStatus.ARCHIVED);
              assertThat(e.action()).isEqualTo("pause");
            });
  }
}
