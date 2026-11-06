package dev.vy.rtb.campaign.domain;

import java.time.DayOfWeek;
import java.util.BitSet;
import java.util.List;
import java.util.Map;

/**
 * The 7 x 24 hours a campaign may run, in the platform time zone (Asia/Ho_Chi_Minh, assumption A9).
 * Bit index = (day of week - 1) * 24 + hour, so Monday 00:00 is bit 0 and Sunday 23:00 is bit 167.
 */
public final class Schedule {

  static final int HOURS_PER_DAY = 24;
  static final int HOURS_PER_WEEK = 7 * HOURS_PER_DAY;

  private final BitSet bits;

  private Schedule(BitSet bits) {
    this.bits = bits;
  }

  /** Every hour allowed. */
  public static Schedule always() {
    BitSet all = new BitSet(HOURS_PER_WEEK);
    all.set(0, HOURS_PER_WEEK);
    return new Schedule(all);
  }

  /** Builds from "all" / "mon".."sun" keys; the caller has already validated keys and hours. */
  static Schedule fromHours(Map<String, List<Integer>> hoursByDay) {
    if (hoursByDay.isEmpty()) {
      return always();
    }
    BitSet bits = new BitSet(HOURS_PER_WEEK);
    for (Map.Entry<String, List<Integer>> entry : hoursByDay.entrySet()) {
      for (DayOfWeek day : daysOf(entry.getKey())) {
        for (int hour : entry.getValue()) {
          bits.set(index(day, hour));
        }
      }
    }
    return new Schedule(bits);
  }

  static boolean isKnownKey(String key) {
    return "all".equals(key) || dayOf(key) != null;
  }

  private static List<DayOfWeek> daysOf(String key) {
    return "all".equals(key) ? List.of(DayOfWeek.values()) : List.of(dayOf(key));
  }

  private static DayOfWeek dayOf(String key) {
    return switch (key) {
      case "mon" -> DayOfWeek.MONDAY;
      case "tue" -> DayOfWeek.TUESDAY;
      case "wed" -> DayOfWeek.WEDNESDAY;
      case "thu" -> DayOfWeek.THURSDAY;
      case "fri" -> DayOfWeek.FRIDAY;
      case "sat" -> DayOfWeek.SATURDAY;
      case "sun" -> DayOfWeek.SUNDAY;
      default -> null;
    };
  }

  private static int index(DayOfWeek day, int hour) {
    return (day.getValue() - 1) * HOURS_PER_DAY + hour;
  }

  public boolean allows(DayOfWeek day, int hour) {
    return bits.get(index(day, hour));
  }

  public int allowedHoursCount() {
    return bits.cardinality();
  }
}
