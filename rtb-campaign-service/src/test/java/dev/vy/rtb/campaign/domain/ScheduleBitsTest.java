package dev.vy.rtb.campaign.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DayOfWeek;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Seam: Schedule.toBitString / fromBitString, the text form of the bit(168) column. */
class ScheduleBitsTest {

  @Test
  void anAlwaysScheduleIsAllOnes() {
    assertThat(Schedule.always().toBitString()).isEqualTo("1".repeat(168));
  }

  @Test
  void mondayMidnightIsTheFirstBitAndSundayElevenPmTheLast() {
    Schedule monday00 = Schedule.fromHours(Map.of("mon", List.of(0)));
    Schedule sunday23 = Schedule.fromHours(Map.of("sun", List.of(23)));

    assertThat(monday00.toBitString()).isEqualTo("1" + "0".repeat(167));
    assertThat(sunday23.toBitString()).isEqualTo("0".repeat(167) + "1");
  }

  @Test
  void eachDayOwnsTwentyFourBitsInWeekdayOrder() {
    // Wednesday is the third day: its hour 5 sits at index 2 * 24 + 5 = 53.
    String bits = Schedule.fromHours(Map.of("wed", List.of(5))).toBitString();

    assertThat(bits.indexOf('1')).isEqualTo(53);
    assertThat(bits.lastIndexOf('1')).isEqualTo(53);
  }

  @Test
  void roundTripsThroughTheTextForm() {
    Schedule original = Schedule.fromHours(Map.of("all", List.of(18, 19, 20, 21, 22)));

    Schedule restored = Schedule.fromBitString(original.toBitString());

    assertThat(restored).isEqualTo(original);
    assertThat(restored.allows(DayOfWeek.FRIDAY, 20)).isTrue();
    assertThat(restored.allows(DayOfWeek.FRIDAY, 23)).isFalse();
  }

  @ParameterizedTest(name = "\"{0}\" is not a valid mask")
  @ValueSource(strings = {"", "101", "2", "abc"})
  void rejectsTextThatIsNotExactly168Bits(String text) {
    assertThatThrownBy(() -> Schedule.fromBitString(text))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejects168CharactersThatAreNotAllZeroOrOne() {
    String almost = "0".repeat(167) + "x";

    assertThatThrownBy(() -> Schedule.fromBitString(almost))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
