package com.agentoffice.controller.usage;

import com.agentoffice.dto.usage.UsageDto;
import com.agentoffice.service.usage.UsageService;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/usage")
public class UsageController {
    private final UsageService usage;

    public UsageController(UsageService usage) { this.usage = usage; }

    /** from/to là ngày (yyyy-MM-dd, gồm cả hai đầu) theo múi giờ báo cáo. Tiền là số nguyên micro-USD. */
    @GetMapping("/summary")
    public List<UsageDto.SummaryRow> summary(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                             @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                             @RequestParam(defaultValue = "day") String groupBy,
                                             @RequestParam(required = false) UUID agentId,
                                             @RequestParam(required = false) UUID projectId) {
        return usage.summary(from, to, groupBy, agentId, projectId);
    }

    @GetMapping("/records")
    public UsageDto.RecordPage records(@RequestParam(required = false) String cursor, @RequestParam(defaultValue = "50") int limit,
                                       @RequestParam(required = false) UUID agentId, @RequestParam(required = false) UUID projectId,
                                       @RequestParam(required = false) UUID taskId) {
        return usage.records(cursor, agentId, projectId, taskId, limit);
    }

    @GetMapping("/unpriced")
    public List<UsageDto.UnpricedRow> unpriced() { return usage.unpriced(); }
}
