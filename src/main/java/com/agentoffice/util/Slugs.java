package com.agentoffice.util;

import java.text.Normalizer;
import java.util.Locale;

public final class Slugs {
    private Slugs() {}

    /** "Công nghệ Web" -> "cong-nghe-web". Chuỗi không còn ký tự hợp lệ thì trả về fallback. */
    public static String of(String name, String fallback) {
        String s = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .replace('đ', 'd').replace('Đ', 'D').toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (s.length() > 50) s = s.substring(0, 50).replaceAll("-+$", "");
        return s.isEmpty() ? fallback : s;
    }
}
