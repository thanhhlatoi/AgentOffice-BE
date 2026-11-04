package com.agentoffice.domain.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentoffice.domain.usage.ProgressCalculator.Item;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** SPEC của ProgressCalculator. */
class ProgressCalculatorTest {
    private final ProgressCalculator calc = new ProgressCalculator();

    private static Item i(int w, String s) { return new Item(w, s); }

    @Test void weightedPercent() {
        assertThat(calc.percent(List.of(i(3, "SUCCEEDED"), i(1, "RUNNING")))).isEqualTo(75);
    }

    @Test void cancelledExcludedFromDenominator() {
        assertThat(calc.percent(List.of(i(1, "SUCCEEDED"), i(5, "CANCELLED")))).isEqualTo(100);
    }

    @Test void emptyIsZero() {
        assertThat(calc.percent(List.of())).isZero();
    }

    @Test void onlyCancelledIsZeroNotADivisionByZero() {
        assertThat(calc.percent(List.of(i(2, "CANCELLED"), i(1, "CANCELLED")))).isZero();
    }

    @Test void failedTaskStaysInTheDenominatorAndLowersProgress() {
        List<Item> ok = List.of(i(1, "SUCCEEDED"), i(1, "SUCCEEDED"), i(1, "RUNNING"));
        List<Item> failed = List.of(i(1, "SUCCEEDED"), i(1, "SUCCEEDED"), i(1, "FAILED"));
        assertThat(calc.percent(ok)).isEqualTo(66);
        assertThat(calc.percent(failed)).isEqualTo(66);                      // FAILED không được loại như CANCELLED
        assertThat(calc.percent(List.of(i(1, "SUCCEEDED"), i(1, "FAILED")))).isEqualTo(50);
        assertThat(calc.percent(List.of(i(1, "SUCCEEDED")))).isEqualTo(100);
        assertThat(calc.percent(List.of(i(1, "SUCCEEDED"), i(1, "FAILED")))).isLessThan(calc.percent(List.of(i(1, "SUCCEEDED"))));
    }

    @Test void roundsDownSoOneHundredMeansReallyDone() {
        List<Item> items = new ArrayList<>();
        for (int k = 0; k < 999; k++) items.add(i(1, "SUCCEEDED"));
        items.add(i(1, "QUEUED"));
        assertThat(calc.percent(items)).isEqualTo(99);                       // 99,9% không được làm tròn lên 100
        assertThat(calc.percent(List.of(i(1, "SUCCEEDED"), i(2, "PENDING")))).isEqualTo(33);
    }

    @Test void everyOpenStatusCountsAsNotDone() {
        for (String s : new String[] {"PENDING", "BLOCKED", "QUEUED", "RUNNING", "WAITING_APPROVAL", "WAITING_BUDGET"})
            assertThat(calc.percent(List.of(i(1, "SUCCEEDED"), i(1, s)))).as(s).isEqualTo(50);
    }

    @Test void sameInputSameOutput() {                                       // tính lại từ dữ liệu: idempotent, không cộng dồn
        List<Item> items = List.of(i(2, "SUCCEEDED"), i(3, "RUNNING"), i(1, "FAILED"));
        assertThat(calc.percent(items)).isEqualTo(calc.percent(items)).isEqualTo(33);
    }

    @Test void bigListsDoNotOverflow() {
        List<Item> items = new ArrayList<>();
        for (int k = 0; k < 3_000_000; k++) items.add(i(5, k % 2 == 0 ? "SUCCEEDED" : "RUNNING"));
        assertThat(calc.percent(items)).isEqualTo(50);
    }

    @Test void unknownStatusAndBadWeightAreRejected() {
        assertThatThrownBy(() -> calc.percent(List.of(i(1, "DONE")))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calc.percent(List.of(i(0, "SUCCEEDED")))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calc.percent(List.of(i(-1, "SUCCEEDED")))).isInstanceOf(IllegalArgumentException.class);
    }
}
