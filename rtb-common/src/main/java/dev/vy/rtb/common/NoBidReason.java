package dev.vy.rtb.common;

/**
 * Why a bid request was answered with "no bid". A no-bid is a valid outcome, not an error; an
 * invalid request is answered with HTTP 400 and is deliberately NOT part of this enum.
 *
 * <p>The same codes are used for debug headers, metrics and reports (see docs/data-model.md).
 */
public enum NoBidReason {
  NO_CANDIDATE(Stage.RETRIEVE),
  GEO_MISMATCH(Stage.RETRIEVE),
  DEVICE_MISMATCH(Stage.RETRIEVE),
  CATEGORY_MISMATCH(Stage.RETRIEVE),

  BLOCKED_PUBLISHER(Stage.FILTER),
  OUT_OF_SCHEDULE(Stage.FILTER),
  BELOW_FLOOR(Stage.FILTER),
  PACING_THROTTLED(Stage.FILTER),

  FREQ_CAP_REACHED(Stage.FREQ),
  FREQ_UNAVAILABLE(Stage.FREQ),
  ANON_LIMITED(Stage.FREQ),

  BUDGET_EXHAUSTED(Stage.RESERVE),
  BUDGET_UNAVAILABLE(Stage.RESERVE),

  DEADLINE_EXCEEDED(Stage.ANY),
  SHED(Stage.GATE);

  /** The step of the bid pipeline that produces the reason. */
  public enum Stage {
    GATE,
    RETRIEVE,
    FILTER,
    FREQ,
    RESERVE,
    ANY
  }

  private final Stage stage;

  NoBidReason(Stage stage) {
    this.stage = stage;
  }

  public Stage stage() {
    return stage;
  }
}
