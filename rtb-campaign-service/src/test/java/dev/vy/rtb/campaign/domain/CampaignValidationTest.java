package dev.vy.rtb.campaign.domain;

import static dev.vy.rtb.campaign.domain.CampaignTestData.ADVERTISER_ID;
import static dev.vy.rtb.campaign.domain.CampaignTestData.ID;
import static dev.vy.rtb.campaign.domain.CampaignTestData.START;
import static dev.vy.rtb.campaign.domain.CampaignTestData.validInput;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Seam: Campaign.draft(...) rejects invalid input with every violation; rules are api-contract 3.3. */
class CampaignValidationTest {

  private static Arguments invalid(
      String label, UnaryOperator<NewCampaign.Builder> change, String field) {
    return Arguments.of(label, change, field);
  }

  static Stream<Arguments> invalidInputs() {
    return Stream.of(
        invalid("empty name", b -> b.name(""), "name"),
        invalid("blank name", b -> b.name("   "), "name"),
        invalid("missing name", b -> b.name(null), "name"),
        invalid("name of 121 characters", b -> b.name("a".repeat(121)), "name"),
        invalid("zero daily budget", b -> b.dailyBudgetMicros(0), "dailyBudgetMicros"),
        invalid("negative daily budget", b -> b.dailyBudgetMicros(-1), "dailyBudgetMicros"),
        invalid(
            "daily budget above total", b -> b.dailyBudgetMicros(1_500_000_001), "totalBudgetMicros"),
        invalid("max bid above daily budget", b -> b.maxBidCpmMicros(100_000_001), "maxBidCpmMicros"),
        invalid("zero max bid", b -> b.maxBidCpmMicros(0), "maxBidCpmMicros"),
        invalid(
            "fixed CPM above max bid", b -> b.bidStrategy(BidStrategy.fixedCpm(2_000_001)), "bidStrategy"),
        invalid("zero fixed CPM", b -> b.bidStrategy(BidStrategy.fixedCpm(0)), "bidStrategy"),
        invalid("zero target CPC", b -> b.bidStrategy(BidStrategy.targetCpc(0)), "bidStrategy"),
        invalid("missing strategy", b -> b.bidStrategy(null), "bidStrategy"),
        invalid("frequency cap of zero", b -> b.freqCapCount(0), "freqCapCount"),
        invalid("end equal to start", b -> b.endAt(START), "endAt"),
        invalid("end before start", b -> b.endAt(START.minusSeconds(1)), "endAt"),
        invalid("missing start", b -> b.startAt(null), "startAt"),
        invalid("missing end", b -> b.endAt(null), "endAt"));
  }

  @ParameterizedTest(name = "{0} is rejected on field {2}")
  @MethodSource("invalidInputs")
  void rejectsInvalidInputOnTheRightField(
      String label, UnaryOperator<NewCampaign.Builder> change, String field) {
    NewCampaign input = change.apply(validInput()).build();

    assertThatThrownBy(() -> Campaign.draft(ID, ADVERTISER_ID, input))
        .isInstanceOfSatisfying(
            CampaignValidationException.class,
            e -> assertThat(e.violations()).extracting(Violation::field).containsExactly(field));
  }

  static Stream<Arguments> boundaryInputs() {
    return Stream.of(
        Arguments.of("name of exactly 120 characters", (UnaryOperator<NewCampaign.Builder>) b -> b.name("a".repeat(120))),
        Arguments.of("daily budget equal to total", (UnaryOperator<NewCampaign.Builder>) b -> b.dailyBudgetMicros(1_500_000_000)),
        Arguments.of("max bid equal to daily budget", (UnaryOperator<NewCampaign.Builder>) b -> b.maxBidCpmMicros(100_000_000).bidStrategy(BidStrategy.fixedCpm(100_000_000))),
        Arguments.of("fixed CPM equal to max bid", (UnaryOperator<NewCampaign.Builder>) b -> b.bidStrategy(BidStrategy.fixedCpm(2_000_000))),
        Arguments.of("frequency cap of one", (UnaryOperator<NewCampaign.Builder>) b -> b.freqCapCount(1)),
        Arguments.of("no frequency cap", (UnaryOperator<NewCampaign.Builder>) b -> b.freqCapCount(null)),
        Arguments.of("target CPC of one micro", (UnaryOperator<NewCampaign.Builder>) b -> b.bidStrategy(BidStrategy.targetCpc(1))));
  }

  @ParameterizedTest(name = "{0} is accepted")
  @MethodSource("boundaryInputs")
  void acceptsValuesOnTheBoundary(String label, UnaryOperator<NewCampaign.Builder> change) {
    NewCampaign input = change.apply(validInput()).build();

    assertThatCode(() -> Campaign.draft(ID, ADVERTISER_ID, input)).doesNotThrowAnyException();
  }

  @Test
  void reportsEveryViolationAtOnceWithoutCascadingFollowUpErrors() {
    // daily budget 0 would also make "max bid <= daily budget" fail; that follow-up must not appear.
    NewCampaign input =
        validInput().name("").dailyBudgetMicros(0).endAt(START.minusSeconds(1)).build();

    assertThatThrownBy(() -> Campaign.draft(ID, ADVERTISER_ID, input))
        .isInstanceOfSatisfying(
            CampaignValidationException.class,
            e ->
                assertThat(e.violations())
                    .extracting(Violation::field)
                    .containsExactlyInAnyOrder("name", "dailyBudgetMicros", "endAt"));
  }

  @Test
  void violationsAreAnUnmodifiableList() {
    NewCampaign input = validInput().name("").build();

    assertThatThrownBy(() -> Campaign.draft(ID, ADVERTISER_ID, input))
        .isInstanceOfSatisfying(
            CampaignValidationException.class,
            e -> {
              List<Violation> violations = e.violations();
              assertThatThrownBy(() -> violations.add(new Violation("x", "y")))
                  .isInstanceOf(UnsupportedOperationException.class);
            });
  }
}
