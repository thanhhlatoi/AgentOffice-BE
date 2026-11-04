package com.agentoffice.domain.knowledge;

import java.util.List;
import java.util.UUID;

/**
 * ===== BẠN TỰ VIẾT ===== Reciprocal Rank Fusion gộp kết quả vector + full-text.
 * score(d) = sum over lists of 1 / (k + rank_in_list(d)), rank bắt đầu từ 1, k thường = 60.
 * Trả về id sắp giảm dần theo score; hoà điểm thì giữ ổn định (deterministic).
 */
public class RrfFusion {
    public List<UUID> fuse(List<List<UUID>> rankedLists, int k, int topN) {
        throw new UnsupportedOperationException("TODO: tự viết");
    }
}
