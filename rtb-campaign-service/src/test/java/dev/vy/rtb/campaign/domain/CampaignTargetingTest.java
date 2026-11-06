package dev.vy.rtb.campaign.domain;

import static dev.vy.rtb.campaign.domain.CampaignTestData.ADVERTISER_ID;
import static dev.vy.rtb.campaign.domain.CampaignTestData.ID;
import static dev.vy.rtb.campaign.domain.CampaignTestData.validInput;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DayOfWeek;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Seam: Campaign.draft(...) validates and normalises targeting (api-contract 3.3, data-model 1.2). */
class CampaignTargetingTest {

  private static TargetingInput targeting(
      List<String> countries,
      List<Integer> deviceTypes,
      List<String> categories,
      List<String> blocklist,
      Map<String, List<Integer>> hours) {
    return new TargetingInput(countries, deviceTypes, categories, blocklist, hours);
  }

  private static TargetingInput onlyCountries(List<String> countries) {
    return targeting(countries, List.of(), List.of(), List.of(), Map.of());
  }

  private static TargetingInput onlyDevices(List<Integer> deviceTypes) {
    return targeting(List.of(), deviceTypes, List.of(), List.of(), Map.of());
  }

  private static TargetingInput onlyHours(Map<String, List<Integer>> hours) {
    return targeting(List.of(), List.of(), List.of(), List.of(), hours);
  }

  private static Campaign draftWith(TargetingInput targeting) {
    return Campaign.draft(ID, ADVERTISER_ID, validInput().targeting(targeting).build());
  }

  private static void assertRejectedOn(TargetingInput targeting, String field) {
    assertThatThrownBy(() -> draftWith(targeting))
        .isInstanceOfSatisfying(
            CampaignValidationException.class,
            e -> assertThat(e.violations()).extracting(Violation::field).containsExactly(field));
  }

  @Test
  void keepsAValidCountryAndDeviceList() {
    Targeting t = draftWith(validInput().build().targeting()).targeting();

    assertThat(t.countries()).containsExactly("VNM");
    assertThat(t.deviceTypes()).containsExactly(1, 4, 5);
  }

  @ParameterizedTest(name = "country code {0} is rejected")
  @ValueSource(strings = {"VN", "XXX", "vnm", "VNMM", ""})
  void rejectsCountryCodesThatAreNotIsoAlpha3(String code) {
    assertRejectedOn(onlyCountries(List.of(code)), "targeting.countries");
  }

  @Test
  void noCountriesMeansEverywhere() {
    assertThat(draftWith(onlyCountries(List.of())).targeting().countries()).isEmpty();
  }

  @ParameterizedTest(name = "device type {0} is rejected")
  @ValueSource(ints = {0, 8, -1})
  void rejectsDeviceTypesOutsideTheOpenRtbRange(int deviceType) {
    assertRejectedOn(onlyDevices(List.of(deviceType)), "targeting.deviceTypes");
  }

  @Test
  void schedulePutsTheGivenHoursOnEveryDay() {
    Schedule schedule =
        draftWith(onlyHours(Map.of("all", List.of(18, 19, 20, 21, 22)))).targeting().schedule();

    assertThat(schedule.allowedHoursCount()).isEqualTo(35); // 5 hours x 7 days
    for (DayOfWeek day : DayOfWeek.values()) {
      assertThat(schedule.allows(day, 17)).isFalse();
      assertThat(schedule.allows(day, 18)).isTrue();
      assertThat(schedule.allows(day, 22)).isTrue();
      assertThat(schedule.allows(day, 23)).isFalse();
    }
  }

  @Test
  void scheduleCanTargetASingleWeekday() {
    Schedule schedule = draftWith(onlyHours(Map.of("sat", List.of(9)))).targeting().schedule();

    assertThat(schedule.allowedHoursCount()).isEqualTo(1);
    assertThat(schedule.allows(DayOfWeek.SATURDAY, 9)).isTrue();
    assertThat(schedule.allows(DayOfWeek.SUNDAY, 9)).isFalse();
  }

  @Test
  void noScheduleMeansAlwaysRunning() {
    Schedule schedule = draftWith(onlyHours(Map.of())).targeting().schedule();

    assertThat(schedule.allowedHoursCount()).isEqualTo(168); // 7 days x 24 hours
    assertThat(schedule.allows(DayOfWeek.MONDAY, 0)).isTrue();
    assertThat(schedule.allows(DayOfWeek.SUNDAY, 23)).isTrue();
  }

  @Test
  void rejectsAnHourOutsideZeroToTwentyThree() {
    assertRejectedOn(onlyHours(Map.of("all", List.of(24))), "targeting.scheduleHours");
    assertRejectedOn(onlyHours(Map.of("all", List.of(-1))), "targeting.scheduleHours");
  }

  @Test
  void rejectsAnUnknownDayKey() {
    assertRejectedOn(onlyHours(Map.of("funday", List.of(9))), "targeting.scheduleHours");
  }

  @Test
  void lowercasesAndDeduplicatesBlocklistAndCategories() {
    TargetingInput input =
        targeting(
            List.of("VNM", "VNM"),
            List.of(1, 1),
            List.of("IAB17", "iab17", "IAB1"),
            List.of("Spam.Example", "spam.example", "Other.COM"),
            Map.of());

    Targeting t = draftWith(input).targeting();

    assertThat(t.publisherBlocklist()).containsExactly("spam.example", "other.com");
    assertThat(t.categories()).containsExactly("iab17", "iab1");
    assertThat(t.countries()).containsExactly("VNM");
    assertThat(t.deviceTypes()).containsExactly(1);
  }

  @Test
  void aMissingTargetingMeansNoRestriction() {
    Targeting t = Campaign.draft(ID, ADVERTISER_ID, validInput().targeting(null).build()).targeting();

    assertThat(t.countries()).isEmpty();
    assertThat(t.schedule().allowedHoursCount()).isEqualTo(168);
  }
}
