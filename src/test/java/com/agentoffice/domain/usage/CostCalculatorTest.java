package com.agentoffice.domain.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CostCalculatorTest {
    private final CostCalculator calc = new CostCalculator();

    @Test void exactMillionTokens() {
        // 1M input @ 3_000_000 micro ($3) + 1M output @ 15_000_000 micro ($15)
        assertThat(calc.costMicroUsd(1_000_000, 1_000_000, 3_000_000, 15_000_000)).isEqualTo(18_000_000);
    }

    @Test void roundsUp() {
        // 1 token input @ 3_000_000/M = 3 micro; 1 token output @ 1/M = 0.000001 -> làm tròn lên 1
        assertThat(calc.costMicroUsd(1, 1, 3_000_000, 1)).isEqualTo(4);
    }

    @Test void zeroTokensZeroCost() {
        assertThat(calc.costMicroUsd(0, 0, 3_000_000, 15_000_000)).isZero();
    }

    @Test void hugeNumbersDoNotOverflow() {
        long cost = calc.costMicroUsd(5_000_000_000L, 0, 3_000_000, 0);
        assertThat(cost).isEqualTo(15_000_000_000L);
    }

    @Test void productOverflowsLongButResultFits() {
        // 1e13 token * 1e8 micro/MTok = 1e21 > Long.MAX_VALUE (9.2e18), nhưng chia 1e6 còn 1e15 vẫn vừa long
        assertThat(calc.costMicroUsd(10_000_000_000_000L, 0, 100_000_000L, 0)).isEqualTo(1_000_000_000_000_000L);
    }

    @Test void resultThatCannotFitLongFailsLoudly() {
        assertThatThrownBy(() -> calc.costMicroUsd(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test void negativeInputsAreRejected() {
        assertThatThrownBy(() -> calc.costMicroUsd(-1, 0, 1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calc.costMicroUsd(1, 1, -1, 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void freeModelCostsNothingEvenWithTokens() {
        assertThat(calc.costMicroUsd(123_456, 654_321, 0, 0)).isZero();
    }
}
