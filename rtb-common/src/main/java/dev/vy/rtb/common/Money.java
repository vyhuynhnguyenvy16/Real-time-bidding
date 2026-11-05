package dev.vy.rtb.common;

/**
 * A non-negative amount of money in micro-USD (1 USD = 1_000_000 micro-USD).
 *
 * <p>Money is a {@code long}, never a floating point number. It can never be negative, so a
 * budget balance that would drop below zero fails loudly instead of silently going negative.
 */
public record Money(long micros) implements Comparable<Money> {

  public static final Money ZERO = new Money(0);

  private static final long IMPRESSIONS_PER_CPM = 1000L;

  public Money {
    if (micros < 0) {
      throw new IllegalArgumentException("Money must not be negative: " + micros);
    }
  }

  public static Money ofMicros(long micros) {
    return new Money(micros);
  }

  /** Adds two amounts; throws {@link ArithmeticException} on overflow. */
  public Money plus(Money other) {
    return new Money(Math.addExact(micros, other.micros));
  }

  /** Subtracts an amount; throws {@link IllegalArgumentException} if the result would be negative. */
  public Money minus(Money other) {
    return new Money(micros - other.micros);
  }

  /**
   * The cost of one impression at the given CPM price (price per 1000 impressions), rounded UP to
   * the next micro-USD so a reservation never under-covers the real cost.
   */
  public static Money costPerImpression(Money cpm) {
    return new Money(Math.ceilDiv(cpm.micros, IMPRESSIONS_PER_CPM));
  }

  @Override
  public int compareTo(Money other) {
    return Long.compare(micros, other.micros);
  }
}
