package com.agentoffice.domain.usage;

import com.agentoffice.domain.task.TaskStatus;
import java.util.List;

/**
 * Tiến độ có trọng số (mục 3.7), dùng cho dự án và goal.
 *
 *   percent = SUCCEEDED.weight / (tổng weight của MỌI task trừ CANCELLED), làm tròn XUỐNG thành số nguyên 0..100.
 *   - Task FAILED nằm ở MẪU SỐ (và không ở tử số): task hỏng làm tiến độ giảm.
 *   - Task CANCELLED bị loại khỏi cả hai.
 *   - Tổng weight của mẫu số = 0 (không có task, hoặc chỉ toàn CANCELLED) => 0.
 *   - Làm tròn xuống: chỉ ra 100 khi thật sự xong hết (99,9% hiển thị 99).
 *   - status là tên TaskStatus; giá trị lạ => IllegalArgumentException. weight <= 0 => IllegalArgumentException.
 *   - Tràn long: tổng weight là int nhưng danh sách có thể rất dài; nhân 100 trước khi chia phải an toàn.
 * Lớp thuần, không biết DB: ProjectService đưa danh sách (weight, status) vào.
 */
public class ProgressCalculator {
    public record Item(int weight, String status) {}

    public int percent(List<Item> items) {
        long done = 0, total = 0;
        for (Item i : items) {
            if (i.weight() <= 0) throw new IllegalArgumentException("weight phải > 0: " + i.weight());
            TaskStatus st = TaskStatus.valueOf(i.status());          // IllegalArgumentException nếu tên lạ
            if (st == TaskStatus.CANCELLED) continue;                // loại khỏi cả tử và mẫu
            total += i.weight();
            if (st == TaskStatus.SUCCEEDED) done += i.weight();
        }
        return total == 0 ? 0 : (int) (done * 100 / total);          // chia nguyên = làm tròn xuống; done*100 không tràn (weight int, danh sách < 2^55 phần tử)
    }
}
