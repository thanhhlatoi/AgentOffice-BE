package com.agentoffice.domain.approval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Chuẩn hoá JSON (khoá sắp xếp, không khoảng trắng) để cùng nội dung ra cùng chuỗi/hash,
 * và sinh idempotencyKey = sha256(taskId | toolName | canonicalArgs). Dùng cho ApprovalGate.request.
 */
public final class CanonicalJson {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private CanonicalJson() {}

    public static String canonicalize(String json) {
        try {
            return MAPPER.writeValueAsString(sort(MAPPER.readTree(json)));
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("args không phải JSON hợp lệ", e);
        }
    }

    private static JsonNode sort(JsonNode n) {
        if (n.isObject()) {
            var out = MAPPER.createObjectNode();
            var names = new java.util.TreeSet<String>();
            n.fieldNames().forEachRemaining(names::add);
            for (String k : names) out.set(k, sort(n.get(k)));
            return out;
        }
        if (n.isArray()) {
            var out = MAPPER.createArrayNode();
            n.forEach(e -> out.add(sort(e)));
            return out;
        }
        return n;
    }

    public static String idempotencyKey(UUID taskId, String toolName, String canonicalArgs) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256")
                    .digest((taskId + "|" + toolName + "|" + canonicalArgs).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
