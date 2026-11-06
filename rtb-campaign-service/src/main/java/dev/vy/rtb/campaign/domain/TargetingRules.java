package dev.vy.rtb.campaign.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Validation and normalisation of {@link TargetingInput} (api-contract 3.3, data-model 1.2). */
final class TargetingRules {

  private static final Set<String> ISO_ALPHA3_COUNTRIES =
      Set.copyOf(Locale.getISOCountries(Locale.IsoCountryCode.PART1_ALPHA3));
  private static final int MIN_DEVICE_TYPE = 1; // OpenRTB devicetype: 1 mobile/tablet .. 7 set top box
  private static final int MAX_DEVICE_TYPE = 7;

  private TargetingRules() {}

  static void validate(TargetingInput in, List<Violation> out) {
    if (in == null) {
      return;
    }
    for (String country : nullToEmpty(in.countries())) {
      if (!ISO_ALPHA3_COUNTRIES.contains(country)) {
        out.add(new Violation("targeting.countries", "not an ISO-3166-1 alpha-3 code: " + country));
      }
    }
    for (int type : nullToEmpty(in.deviceTypes())) {
      if (type < MIN_DEVICE_TYPE || type > MAX_DEVICE_TYPE) {
        out.add(new Violation("targeting.deviceTypes", "must be 1-7: " + type));
      }
    }
    validateSchedule(nullToEmptyMap(in.scheduleHours()), out);
  }

  private static void validateSchedule(Map<String, List<Integer>> hoursByDay, List<Violation> out) {
    for (Map.Entry<String, List<Integer>> entry : hoursByDay.entrySet()) {
      if (!Schedule.isKnownKey(entry.getKey())) {
        out.add(new Violation("targeting.scheduleHours", "unknown day key: " + entry.getKey()));
        continue;
      }
      for (int hour : entry.getValue()) {
        if (hour < 0 || hour >= Schedule.HOURS_PER_DAY) {
          out.add(new Violation("targeting.scheduleHours", "hour must be 0-23: " + hour));
        }
      }
    }
  }

  /** Call only after {@link #validate} found no violation. */
  static Targeting normalise(TargetingInput in) {
    if (in == null) {
      return Targeting.unrestricted();
    }
    return new Targeting(
        List.copyOf(new LinkedHashSet<>(nullToEmpty(in.countries()))),
        List.copyOf(new LinkedHashSet<>(nullToEmpty(in.deviceTypes()))),
        lowercaseDistinct(nullToEmpty(in.categories())),
        lowercaseDistinct(nullToEmpty(in.publisherBlocklist())),
        Schedule.fromHours(nullToEmptyMap(in.scheduleHours())));
  }

  private static List<String> lowercaseDistinct(List<String> values) {
    LinkedHashSet<String> distinct = new LinkedHashSet<>();
    for (String value : values) {
      distinct.add(value.toLowerCase(Locale.ROOT));
    }
    return List.copyOf(distinct);
  }

  private static <T> List<T> nullToEmpty(List<T> list) {
    return list == null ? new ArrayList<>() : list;
  }

  private static Map<String, List<Integer>> nullToEmptyMap(Map<String, List<Integer>> map) {
    return map == null ? Map.of() : map;
  }
}
