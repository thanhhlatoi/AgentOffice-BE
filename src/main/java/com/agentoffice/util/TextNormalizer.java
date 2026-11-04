package com.agentoffice.util;

import java.text.Normalizer;
import java.util.Locale;

/**
 * VÍ DỤ ĐÃ VIẾT SẴN (để bạn xem phong cách code + test). Bỏ dấu tiếng Việt cho tìm kiếm.
 * "Hội thoại Đặng" -> "hoi thoai dang"
 */
public final class TextNormalizer {
    private TextNormalizer() {}

    public static String forSearch(String s) {
        if (s == null) return "";
        String decomposed = Normalizer.normalize(s, Normalizer.Form.NFD);
        String noMarks = decomposed.replaceAll("\\p{M}+", "");
        return noMarks.replace('đ', 'd').replace('Đ', 'D').toLowerCase(Locale.ROOT).trim();
    }
}
