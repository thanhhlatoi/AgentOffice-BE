package com.agentoffice.domain.task;

import com.agentoffice.config.LimitsProperties;
import com.agentoffice.domain.task.Plan.SubTask;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Lớp THUẦN. Trả danh sách lỗi CỤ THỂ (tiếng Việt, nói rõ key nào sai) để đưa lại cho Planner làm lại; rỗng = hợp lệ.
 * Gom MỌI lỗi thay vì dừng ở lỗi đầu: Planner sửa được nhiều lỗi một lượt.
 * Độ sâu = số task trên đường phụ thuộc DÀI NHẤT (A -> B -> C là 3). Chỉ tính khi không có chu trình.
 */
public class PlanValidator {
    static final int MAX_TITLE = 300;
    static final int MAX_INPUT = 20000;

    private final LimitsProperties limits;

    public PlanValidator(LimitsProperties limits) { this.limits = limits; }

    public List<String> validate(Plan plan, List<AgentInfo> agents) {
        List<String> errors = new ArrayList<>();
        List<SubTask> tasks = plan == null ? List.of() : plan.tasks();
        if (tasks.isEmpty()) {
            errors.add("Kế hoạch không có task nào");
            return errors;
        }
        if (tasks.size() > limits.maxTasksPerGoal())
            errors.add("Kế hoạch có " + tasks.size() + " task, tối đa " + limits.maxTasksPerGoal());

        Map<UUID, AgentInfo> agentById = new HashMap<>();
        for (AgentInfo a : agents) agentById.put(a.id(), a);

        // ---- key
        Map<String, SubTask> byKey = new LinkedHashMap<>();
        Set<String> reportedDuplicates = new HashSet<>();
        for (int i = 0; i < tasks.size(); i++) {
            String key = tasks.get(i).key();
            if (key == null || key.isBlank()) { errors.add("Task thứ " + (i + 1) + " thiếu key"); continue; }
            if (byKey.putIfAbsent(key, tasks.get(i)) != null && reportedDuplicates.add(key)) errors.add("Key '" + key + "' bị trùng");
        }

        // ---- từng task
        for (int i = 0; i < tasks.size(); i++) {
            SubTask t = tasks.get(i);
            String who = t.key() == null || t.key().isBlank() ? "task thứ " + (i + 1) : "task '" + t.key() + "'";
            if (t.title() == null || t.title().isBlank()) errors.add(who + ": thiếu title");
            else if (t.title().length() > MAX_TITLE) errors.add(who + ": title dài " + t.title().length() + " ký tự, tối đa " + MAX_TITLE);
            if (t.input() == null || t.input().isBlank()) errors.add(who + ": thiếu input");
            else if (t.input().length() > MAX_INPUT) errors.add(who + ": input dài " + t.input().length() + " ký tự, tối đa " + MAX_INPUT);

            if (t.assigneeAgentId() == null) errors.add(who + ": thiếu assigneeAgentId");
            else {
                AgentInfo a = agentById.get(t.assigneeAgentId());
                if (a == null) errors.add(who + ": agent " + t.assigneeAgentId() + " không tồn tại");
                else if ("OFFLINE".equals(a.status())) errors.add(who + ": agent '" + a.name() + "' đang OFFLINE");
            }

            for (String dep : t.dependsOnKeys()) {
                if (dep != null && dep.equals(t.key())) errors.add(who + ": phụ thuộc chính nó");
                else if (dep == null || !byKey.containsKey(dep)) errors.add(who + ": phụ thuộc key không tồn tại '" + dep + "'");
            }
        }

        // ---- đồ thị phụ thuộc (chỉ các cạnh hợp lệ, không tự trỏ)
        Map<String, Set<String>> deps = new LinkedHashMap<>();       // key -> các key nó phụ thuộc
        for (Map.Entry<String, SubTask> e : byKey.entrySet()) {
            Set<String> d = new LinkedHashSet<>();
            for (String k : e.getValue().dependsOnKeys()) if (k != null && !k.equals(e.getKey()) && byKey.containsKey(k)) d.add(k);
            deps.put(e.getKey(), d);
        }
        List<String> order = topologicalOrder(deps);
        if (order.size() < deps.size()) {
            errors.add("Phụ thuộc có chu trình giữa các task: " + String.join(", ", cycleMembers(deps, order)));
            return errors;                                           // có chu trình thì "đường dài nhất" vô nghĩa
        }
        Map<String, Integer> depth = new HashMap<>();
        Map<String, String> via = new HashMap<>();                   // để in đường dài nhất
        String deepest = null;
        for (String k : order) {
            int best = 0;
            String from = null;
            for (String d : deps.get(k)) if (depth.get(d) > best) { best = depth.get(d); from = d; }
            depth.put(k, best + 1);
            via.put(k, from);
            if (deepest == null || depth.get(k) > depth.get(deepest)) deepest = k;
        }
        if (deepest != null && depth.get(deepest) > limits.maxTaskDepth()) {
            List<String> path = new ArrayList<>();
            for (String k = deepest; k != null; k = via.get(k)) path.add(0, k);
            errors.add("Chuỗi phụ thuộc dài " + depth.get(deepest) + " task (" + String.join(" -> ", path) + "), tối đa " + limits.maxTaskDepth());
        }
        return errors;
    }

    /** Kahn: thứ tự mà mỗi task đứng SAU mọi phụ thuộc của nó. Thiếu phần tử = có chu trình. */
    private static List<String> topologicalOrder(Map<String, Set<String>> deps) {
        Map<String, Integer> remaining = new HashMap<>();
        Map<String, List<String>> dependents = new HashMap<>();
        for (var e : deps.entrySet()) {
            remaining.put(e.getKey(), e.getValue().size());
            for (String d : e.getValue()) dependents.computeIfAbsent(d, x -> new ArrayList<>()).add(e.getKey());
        }
        List<String> order = new ArrayList<>();
        List<String> ready = new ArrayList<>();
        for (var e : remaining.entrySet()) if (e.getValue() == 0) ready.add(e.getKey());
        for (int i = 0; i < ready.size(); i++) {
            String k = ready.get(i);
            order.add(k);
            for (String dep : dependents.getOrDefault(k, List.of())) if (remaining.merge(dep, -1, Integer::sum) == 0) ready.add(dep);
        }
        return order;
    }

    /** Phần còn lại sau Kahn gồm chu trình và các task phụ thuộc vào nó; gọt các task chẳng ai phụ thuộc để chỉ còn thành viên chu trình. */
    private static List<String> cycleMembers(Map<String, Set<String>> deps, List<String> sorted) {
        Set<String> left = new LinkedHashSet<>(deps.keySet());
        sorted.forEach(left::remove);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String k : new ArrayList<>(left)) {
                boolean hasDependent = false;
                for (String other : left) if (deps.get(other).contains(k)) { hasDependent = true; break; }
                if (!hasDependent) { left.remove(k); changed = true; }
            }
        }
        return new ArrayList<>(left);
    }
}
