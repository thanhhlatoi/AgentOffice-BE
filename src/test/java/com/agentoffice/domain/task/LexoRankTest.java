package com.agentoffice.domain.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Random;
import org.junit.jupiter.api.Test;

/** SPEC của LexoRank. Xem hợp đồng trong Javadoc LexoRank. */
class LexoRankTest {
    private final LexoRank lr = new LexoRank();
    private static final String ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz";

    private void assertValid(String r) {
        assertThat(r).isNotEmpty().matches("[0-9a-z]+");
        assertThat(r).as("không kết thúc bằng '0'").doesNotEndWith("0");
    }

    private void assertBetween(String before, String after) {
        String r = lr.between(before, after);
        assertValid(r);
        if (before != null) assertThat(r.compareTo(before)).as("%s > %s", r, before).isPositive();
        if (after != null) assertThat(r.compareTo(after)).as("%s < %s", r, after).isNegative();
    }

    @Test void betweenTwoNeighbours() {
        assertBetween("a", "c");
        assertBetween("a", "b");                 // kề nhau: phải kéo dài chuỗi
        assertBetween("az", "b");
        assertBetween("a", "a1");
        assertBetween("a", "az");
    }

    @Test void openEnds() {
        assertBetween("a", null);
        assertBetween(null, "m");
        assertBetween("zzzz", null);
        assertBetween(null, "1");
        assertValid(lr.between(null, null));
    }

    @Test void worksWithTheTwentyDigitRanksTheDatabaseGenerates() {
        assertBetween("00000000000000000005", "00000000000000000006");
        assertBetween("00000000000000000005", "00000000000000000009");
        assertBetween(null, "00000000000000000001");
        assertBetween("00000000000000000042", null);
    }

    @Test void differentLengthsAreComparedAsFractions() {
        assertBetween("5", "50001");
        assertBetween("50001", "6");
        assertBetween("abc", "abd");
    }

    @Test void repeatedInsertAtTheSameSpotKeepsOrderAndStaysShort() {
        String lo = "a", hi = "b";
        for (int k = 0; k < 100; k++) {
            String mid = lr.between(lo, hi);
            assertThat(mid.compareTo(lo)).isPositive();
            assertThat(mid.compareTo(hi)).isNegative();
            hi = mid;                            // luôn chèn vào khe sát cận dưới: trường hợp xấu nhất
        }
        assertThat(hi.length()).as("100 lần chèn cùng chỗ").isLessThanOrEqualTo(100);
    }

    @Test void repeatedAppendAtTheEndGrowsSlowly() {
        String last = "00000000000000000042";
        for (int k = 0; k < 1000; k++) {
            String next = lr.between(last, null);
            assertThat(next.compareTo(last)).isPositive();
            last = next;
        }
        assertThat(last.length()).as("1000 lần thêm vào cuối").isLessThan(60);
    }

    @Test void repeatedPrependAtTheStartKeepsOrder() {
        String first = "00000000000000000500";
        for (int k = 0; k < 200; k++) {
            String prev = lr.between(null, first);
            assertThat(prev.compareTo(first)).isNegative();
            assertValid(prev);
            first = prev;
        }
    }

    @Test void invalidArgumentsAreRejected() {
        assertThatThrownBy(() -> lr.between("b", "a")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> lr.between("a", "a")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> lr.between("a$", "b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> lr.between("", "b")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void noRoomIsReportedNotSilentlyWrong() {
        assertThatThrownBy(() -> lr.between(null, "0")).isInstanceOf(IllegalStateException.class);
    }

    @Test void fuzz() {
        Random rnd = new Random(42);
        for (int k = 0; k < 2000; k++) {
            String a = randomRank(rnd), b = randomRank(rnd);
            int c = a.compareTo(b);
            if (c == 0) continue;
            assertBetween(c < 0 ? a : b, c < 0 ? b : a);
        }
    }

    private static String randomRank(Random rnd) {
        int len = 1 + rnd.nextInt(6);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) sb.append(ALPHABET.charAt(rnd.nextInt(ALPHABET.length())));
        if (sb.charAt(len - 1) == '0') sb.setCharAt(len - 1, '1');       // hạng hợp lệ không kết thúc bằng '0'
        return sb.toString();
    }
}
