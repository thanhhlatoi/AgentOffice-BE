package com.agentoffice.domain.task;

import java.math.BigInteger;

/**
 * Xếp hạng bằng CHUỖI để chèn giữa hai phần tử mà không phải đánh số lại cả cột.
 *
 * Hợp đồng (test đỏ trong LexoRankTest):
 *  - Bảng chữ: '0'..'9' rồi 'a'..'z' (thứ tự ASCII = thứ tự cột kanban_rank trong DB, COLLATE "C" = so sánh byte).
 *  - between(before, after): trả chuỗi r sao cho before < r < after (so sánh từ điển, String.compareTo).
 *      before == null: không có cận dưới (chèn lên ĐẦU).   after == null: không có cận trên (chèn xuống CUỐI).
 *      cả hai null: trả một hạng khởi đầu bất kỳ (nên ở giữa bảng chữ để còn chỗ cả hai phía).
 *      before >= after (cả hai khác null) => IllegalArgumentException.
 *      Không còn chỗ (vd after = "0", không chuỗi nào nhỏ hơn mà hợp lệ) => IllegalStateException.
 *  - Kết quả CHỈ gồm ký tự trong bảng chữ và KHÔNG kết thúc bằng '0' (kết thúc bằng '0' thì không bao giờ chèn
 *    được phía trước nó nữa; vd không có chuỗi nào nằm giữa "a" và "a0").
 *  - Chèn lặp lại cùng một chỗ (luôn between(x, y) rồi lấy kết quả làm cận) không được làm chuỗi dài lên quá nhanh
 *    ngoài mức chấp nhận được; KanbanRankJob sẽ dồn lại (đánh số lại) khi chuỗi > 40 ký tự.
 *
 * Cẩn thận hai đầu: thêm liên tiếp xuống CUỐI (after == null) là trường hợp phổ biến nhất. Nếu mỗi lần bạn chia đôi
 * khoảng còn lại tới "vô cực" thì chuỗi dài thêm 1 ký tự sau mỗi vài lần. Hãy nghĩ cách khác cho đầu mở (LexoRankTest
 * đo: 1000 lần thêm vào cuối, chuỗi chỉ được dài thêm vài ký tự). Đầu trên (before == null) chỉ cần đúng thứ tự.
 *
 * Hạng sinh ra bởi DB cho task mới là số 20 chữ số (vd "00000000000000000042"): between phải làm việc đúng với chúng.
 * Gợi ý: coi chuỗi như số thập phân/phân số trong hệ cơ số 36, tìm "trung điểm"; độ dài hai chuỗi có thể khác nhau
 * (đệm '0' vào bên phải khi so sánh từng vị trí).
 */
public class LexoRank {
    private static final String ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz";
    private static final int BASE = ALPHABET.length();
    private static final BigInteger B = BigInteger.valueOf(BASE);
    /** Hạng khởi đầu: giữa bảng chữ, còn chỗ cả hai phía. */
    private static final String START = "i";

    public String between(String before, String after) {
        validate(before);
        validate(after);
        if (before != null && after != null && before.compareTo(after) >= 0)
            throw new IllegalArgumentException("before phải nhỏ hơn after: " + before + " >= " + after);
        if (before == null && after == null) return START;
        if (after == null) return append(before);
        if (before == null) return prepend(after);
        return middle(before, after);
    }

    /** Đầu mở trên: tăng như bộ đếm cơ số 36 (giữ độ dài, không chia đôi khoảng tới vô cực); tràn thì nối thêm ký tự. */
    private static String append(String before) {
        String next = increment(before);
        if (next == null) return before + "i";                       // toàn 'z': nối thêm
        if (next.endsWith("0")) {                                    // vừa nhảy qua 'z' -> '0': bỏ qua giá trị kết thúc bằng '0'
            String again = increment(next);
            return again == null ? next + "i" : again;
        }
        return next;
    }

    /** Đầu mở dưới: giảm như bộ đếm; nếu kết quả kết thúc bằng '0' thì nối 'i' (vẫn nhỏ hơn `after` vì đã khác nhau trong n ký tự đầu). */
    private static String prepend(String after) {
        String prev = decrement(after);
        if (prev == null) throw new IllegalStateException("Không còn chỗ phía trước " + after);
        return prev.endsWith("0") ? prev + "i" : prev;
    }

    /** Coi hai chuỗi là phân số cơ số 36 (đệm '0' bên phải), lấy trung điểm; hết chỗ ở độ dài này thì thử dài hơn. */
    private static String middle(String before, String after) {
        int len = Math.max(before.length(), after.length());
        if (value(before, len).equals(value(after, len))) throw new IllegalStateException("Không có hạng nào giữa " + before + " và " + after);
        for (;; len++) {
            BigInteger x = value(before, len), y = value(after, len), mid = x.add(y).shiftRight(1);
            if (mid.equals(x)) continue;
            if (text(mid, len).endsWith("0")) mid = mid.add(BigInteger.ONE);     // kết quả không kết thúc bằng '0'
            if (mid.compareTo(y) >= 0) continue;
            return text(mid, len);
        }
    }

    private static void validate(String s) {
        if (s == null) return;
        if (s.isEmpty()) throw new IllegalArgumentException("hạng rỗng");
        for (int i = 0; i < s.length(); i++)
            if (ALPHABET.indexOf(s.charAt(i)) < 0) throw new IllegalArgumentException("ký tự không hợp lệ '" + s.charAt(i) + "' trong " + s);
    }

    private static BigInteger value(String s, int len) {
        BigInteger v = BigInteger.ZERO;
        for (int i = 0; i < len; i++) v = v.multiply(B).add(BigInteger.valueOf(i < s.length() ? ALPHABET.indexOf(s.charAt(i)) : 0));
        return v;
    }

    private static String text(BigInteger v, int len) {
        char[] out = new char[len];
        for (int i = len - 1; i >= 0; i--) {
            BigInteger[] qr = v.divideAndRemainder(B);
            out[i] = ALPHABET.charAt(qr[1].intValue());
            v = qr[0];
        }
        return new String(out);
    }

    /** s + 1 cùng độ dài; null nếu tràn (toàn 'z'). */
    private static String increment(String s) {
        BigInteger v = value(s, s.length()).add(BigInteger.ONE);
        return v.compareTo(B.pow(s.length())) >= 0 ? null : text(v, s.length());
    }

    /** s - 1 cùng độ dài; null nếu s toàn '0'. */
    private static String decrement(String s) {
        BigInteger v = value(s, s.length());
        return v.signum() == 0 ? null : text(v.subtract(BigInteger.ONE), s.length());
    }
}
