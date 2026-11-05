package dev.vy.rtb.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vy.rtb.common.NoBidReason.Stage;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Seam: the public interface of {@link NoBidReason}. The expected table is copied from
 * docs/data-model.md section 4, so this test fails when code and spec drift apart.
 */
class NoBidReasonTest {

  @Test
  void hasExactlyTheFifteenReasonsOfTheDataModel() {
    assertThat(Arrays.stream(NoBidReason.values()).map(Enum::name))
        .containsExactlyInAnyOrder(
            "NO_CANDIDATE",
            "GEO_MISMATCH",
            "DEVICE_MISMATCH",
            "CATEGORY_MISMATCH",
            "BLOCKED_PUBLISHER",
            "OUT_OF_SCHEDULE",
            "BELOW_FLOOR",
            "PACING_THROTTLED",
            "FREQ_CAP_REACHED",
            "FREQ_UNAVAILABLE",
            "ANON_LIMITED",
            "BUDGET_EXHAUSTED",
            "BUDGET_UNAVAILABLE",
            "DEADLINE_EXCEEDED",
            "SHED");
  }

  @ParameterizedTest(name = "{0} belongs to stage {1}")
  @CsvSource({
    "NO_CANDIDATE,RETRIEVE",
    "GEO_MISMATCH,RETRIEVE",
    "DEVICE_MISMATCH,RETRIEVE",
    "CATEGORY_MISMATCH,RETRIEVE",
    "BLOCKED_PUBLISHER,FILTER",
    "OUT_OF_SCHEDULE,FILTER",
    "BELOW_FLOOR,FILTER",
    "PACING_THROTTLED,FILTER",
    "FREQ_CAP_REACHED,FREQ",
    "FREQ_UNAVAILABLE,FREQ",
    "ANON_LIMITED,FREQ",
    "BUDGET_EXHAUSTED,RESERVE",
    "BUDGET_UNAVAILABLE,RESERVE",
    "DEADLINE_EXCEEDED,ANY",
    "SHED,GATE"
  })
  void eachReasonBelongsToTheStageOfTheDataModel(String reason, String stage) {
    assertThat(NoBidReason.valueOf(reason).stage()).isEqualTo(Stage.valueOf(stage));
  }

  @Test
  void invalidRequestIsAnErrorNotANoBid() {
    assertThatThrownBy(() -> NoBidReason.valueOf("INVALID_REQUEST"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
