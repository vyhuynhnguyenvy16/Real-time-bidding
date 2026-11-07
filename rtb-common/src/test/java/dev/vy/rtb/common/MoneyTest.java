package dev.vy.rtb.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Seam: the public interface of {@link Money}. Expected values are written as literals (1 USD =
 * 1_000_000 micro-USD, CPM = price per 1000 impressions), never recomputed with the same formula as
 * the implementation.
 */
class MoneyTest {

  @Test
  void zeroHasNoMicros() {
    assertThat(Money.ZERO.micros()).isZero();
  }

  @Test
  void rejectsNegativeAmounts() {
    assertThatThrownBy(() -> Money.ofMicros(-1)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void plusAddsAmounts() {
    Money total = Money.ofMicros(1_000_000).plus(Money.ofMicros(250_000));

    assertThat(total).isEqualTo(Money.ofMicros(1_250_000));
  }

  @Test
  void plusFailsLoudlyOnOverflowInsteadOfWrappingAround() {
    assertThatThrownBy(() -> Money.ofMicros(Long.MAX_VALUE).plus(Money.ofMicros(1)))
        .isInstanceOf(ArithmeticException.class);
  }

  @Test
  void minusSubtractsAmounts() {
    // BA example: 1.00 USD budget, 0.60 USD reserved, 0.40 USD left.
    Money left = Money.ofMicros(1_000_000).minus(Money.ofMicros(600_000));

    assertThat(left).isEqualTo(Money.ofMicros(400_000));
  }

  @Test
  void minusNeverProducesANegativeBalance() {
    assertThatThrownBy(() -> Money.ofMicros(400_000).minus(Money.ofMicros(600_000)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void ordersByAmount() {
    assertThat(Money.ofMicros(400_000)).isLessThan(Money.ofMicros(600_000));
    assertThat(Money.ofMicros(600_000)).isGreaterThan(Money.ofMicros(400_000));
    assertThat(Money.ofMicros(600_000)).isEqualByComparingTo(Money.ofMicros(600_000));
  }

  @ParameterizedTest(name = "CPM {0} micro-USD costs {1} micro-USD per impression")
  @CsvSource({
    "0,0",
    "1,1",
    "999,1",
    "1000,1",
    "1001,2",
    "850000,850", // CPM 0.85 USD
    "850001,851", // rounds UP: never under-reserve
    "2000000,2000" // CPM 2.00 USD
  })
  void costPerImpressionRoundsUpToTheNextMicro(long cpmMicros, long expectedMicros) {
    Money cost = Money.costPerImpression(Money.ofMicros(cpmMicros));

    assertThat(cost).isEqualTo(Money.ofMicros(expectedMicros));
  }

  @Test
  void costPerImpressionDoesNotOverflowForHugePrices() {
    Money cost = Money.costPerImpression(Money.ofMicros(Long.MAX_VALUE));

    assertThat(cost).isEqualTo(Money.ofMicros(9_223_372_036_854_776L));
  }
}
