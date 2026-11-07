package dev.vy.rtb.campaign.domain;

import java.util.List;

/** Validated, normalised targeting. Empty lists mean "no restriction". */
public record Targeting(
    List<String> countries,
    List<Integer> deviceTypes,
    List<String> categories,
    List<String> publisherBlocklist,
    Schedule schedule) {

  static Targeting unrestricted() {
    return new Targeting(List.of(), List.of(), List.of(), List.of(), Schedule.always());
  }
}
