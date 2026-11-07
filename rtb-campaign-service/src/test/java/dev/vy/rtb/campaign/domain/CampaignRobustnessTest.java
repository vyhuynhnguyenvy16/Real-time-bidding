package dev.vy.rtb.campaign.domain;

import static dev.vy.rtb.campaign.domain.CampaignTestData.ADVERTISER_ID;
import static dev.vy.rtb.campaign.domain.CampaignTestData.ID;
import static dev.vy.rtb.campaign.domain.CampaignTestData.validInput;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Seam: Campaign.draft(...). Input that comes straight from JSON can hold nulls and empty values;
 * each of them must become a Violation, never an exception from inside the validator.
 */
class CampaignRobustnessTest {

  private static TargetingInput targeting(
      List<String> countries,
      List<Integer> deviceTypes,
      List<String> categories,
      List<String> blocklist,
      Map<String, List<Integer>> hours) {
    return new TargetingInput(countries, deviceTypes, categories, blocklist, hours);
  }

  private static Map<String, List<Integer>> hoursWithNullKey() {
    Map<String, List<Integer>> hours = new HashMap<>();
    hours.put(null, List.of(9));
    return hours;
  }

  private static Map<String, List<Integer>> hoursWithNullList() {
    Map<String, List<Integer>> hours = new HashMap<>();
    hours.put("all", null);
    return hours;
  }

  private static Arguments invalid(
      String label, UnaryOperator<NewCampaign.Builder> change, String field) {
    return Arguments.of(label, change, field);
  }

  static Stream<Arguments> inputsThatMustNotCrash() {
    return Stream.of(
        invalid(
            "null country",
            b ->
                b.targeting(
                    targeting(
                        Arrays.asList("VNM", null), List.of(), List.of(), List.of(), Map.of())),
            "targeting.countries"),
        invalid(
            "null device type",
            b ->
                b.targeting(
                    targeting(List.of(), Arrays.asList(1, null), List.of(), List.of(), Map.of())),
            "targeting.deviceTypes"),
        invalid(
            "null category",
            b ->
                b.targeting(
                    targeting(
                        List.of(), List.of(), Arrays.asList("iab1", null), List.of(), Map.of())),
            "targeting.categories"),
        invalid(
            "null blocklist entry",
            b ->
                b.targeting(
                    targeting(
                        List.of(), List.of(), List.of(), Arrays.asList("a.com", null), Map.of())),
            "targeting.publisherBlocklist"),
        invalid(
            "null day key",
            b ->
                b.targeting(
                    targeting(List.of(), List.of(), List.of(), List.of(), hoursWithNullKey())),
            "targeting.scheduleHours"),
        invalid(
            "null hour list",
            b ->
                b.targeting(
                    targeting(List.of(), List.of(), List.of(), List.of(), hoursWithNullList())),
            "targeting.scheduleHours"),
        invalid(
            "null hour",
            b ->
                b.targeting(
                    targeting(
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        Map.of("all", Arrays.asList(9, null)))),
            "targeting.scheduleHours"),
        invalid(
            "an empty hour list, which would never run",
            b ->
                b.targeting(
                    targeting(
                        List.of(), List.of(), List.of(), List.of(), Map.of("all", List.of()))),
            "targeting.scheduleHours"),
        invalid(
            "a strategy without a type",
            b -> b.bidStrategy(new BidStrategy(null, 850_000)),
            "bidStrategy"),
        invalid("a missing pacing mode", b -> b.pacingMode(null), "pacingMode"));
  }

  @ParameterizedTest(name = "{0} gives one violation on {2}")
  @MethodSource("inputsThatMustNotCrash")
  void turnsBadInputIntoOneViolation(
      String label, UnaryOperator<NewCampaign.Builder> change, String field) {
    NewCampaign input = change.apply(validInput()).build();

    assertThatThrownBy(() -> Campaign.draft(ID, ADVERTISER_ID, input))
        .isInstanceOfSatisfying(
            CampaignValidationException.class,
            e -> assertThat(e.violations()).extracting(Violation::field).containsExactly(field));
  }

  @Test
  void aMissingIdentityIsAProgrammingErrorNotAViolation() {
    NewCampaign input = validInput().build();

    assertThatThrownBy(() -> Campaign.draft(null, ADVERTISER_ID, input))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> Campaign.draft(ID, null, input))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void twoSchedulesBuiltFromTheSameHoursAreEqual() {
    Targeting first = Campaign.draft(ID, ADVERTISER_ID, validInput().build()).targeting();
    Targeting second = Campaign.draft(ID, ADVERTISER_ID, validInput().build()).targeting();

    assertThat(first.schedule()).isEqualTo(second.schedule());
    assertThat(first).isEqualTo(second);
    assertThat(first.schedule()).isNotEqualTo(Schedule.always());
  }
}
