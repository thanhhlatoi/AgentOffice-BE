package com.agentoffice.domain.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentoffice.config.LimitsProperties;
import com.agentoffice.domain.task.Plan.SubTask;
import com.agentoffice.domain.task.Priority;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Spec cho PlanValidator. Lớp thuần, giới hạn 3 độ sâu / 20 task. */
@Tag("core")
class PlanValidatorTest {
    private final PlanValidator v = new PlanValidator(new LimitsProperties(3, 20));
    private final UUID a1 = UUID.randomUUID(), a2 = UUID.randomUUID(), off = UUID.randomUUID();
    private final List<AgentInfo> agents = List.of(
            new AgentInfo(a1, "A", "r", "d", "IDLE"), new AgentInfo(a2, "B", "r", "d", "WORKING"),
            new AgentInfo(off, "Off", "r", "d", "OFFLINE"));

    private static SubTask t(String key, UUID agent, String... deps) {
        return new SubTask(key, "title " + key, "input " + key, agent, Priority.NORMAL, List.of(deps));
    }

    private List<String> errors(SubTask... ts) { return v.validate(new Plan(List.of(ts)), agents); }

    @Test void validPlanHasNoErrors() {
        assertThat(errors(t("a", a1), t("b", a2, "a"), t("c", a1, "a", "b"))).isEmpty();
    }

    @Test void emptyPlanIsRejected() {
        assertThat(errors()).isNotEmpty();
    }

    @Test void duplicateKeys() {
        assertThat(errors(t("a", a1), t("a", a2))).anyMatch(s -> s.contains("a"));
    }

    @Test void blankKeyTitleOrInput() {
        assertThat(errors(new SubTask(" ", "t", "i", a1, null, null))).isNotEmpty();
        assertThat(errors(new SubTask("a", " ", "i", a1, null, null))).isNotEmpty();
        assertThat(errors(new SubTask("a", "t", "", a1, null, null))).isNotEmpty();
    }

    @Test void overlongTitleOrInput() {
        assertThat(errors(new SubTask("a", "x".repeat(301), "i", a1, null, null))).isNotEmpty();
        assertThat(errors(new SubTask("a", "t", "x".repeat(20001), a1, null, null))).isNotEmpty();
    }

    @Test void unknownAssignee() {
        assertThat(errors(t("a", UUID.randomUUID()))).anyMatch(s -> s.contains("a"));
    }

    @Test void nullAssignee() {
        assertThat(errors(t("a", null))).isNotEmpty();
    }

    @Test void offlineAssignee() {
        assertThat(errors(t("a", off))).anyMatch(s -> s.contains("a"));
    }

    @Test void unknownDependencyKey() {
        assertThat(errors(t("a", a1, "zzz"))).anyMatch(s -> s.contains("zzz"));
    }

    @Test void selfDependency() {
        assertThat(errors(t("a", a1, "a"))).isNotEmpty();
    }

    @Test void directCycle() {
        assertThat(errors(t("a", a1, "b"), t("b", a2, "a"))).anyMatch(s -> s.contains("a") && s.contains("b"));
    }

    @Test void longCycleIsReportedNotHangs() {
        assertThat(errors(t("a", a1, "c"), t("b", a1, "a"), t("c", a1, "b"), t("d", a1))).isNotEmpty();
    }

    @Test void depthThreeIsOkDepthFourIsNot() {
        assertThat(errors(t("a", a1), t("b", a1, "a"), t("c", a1, "b"))).isEmpty();
        assertThat(errors(t("a", a1), t("b", a1, "a"), t("c", a1, "b"), t("d", a1, "c"))).isNotEmpty();
    }

    @Test void diamondShapeCountsLongestPathOnly() {
        assertThat(errors(t("a", a1), t("b", a1, "a"), t("c", a1, "a"), t("d", a1, "b", "c"))).isEmpty();
    }

    @Test void twentyTasksOkTwentyOneNot() {
        assertThat(v.validate(new Plan(many(20)), agents)).isEmpty();
        assertThat(v.validate(new Plan(many(21)), agents)).isNotEmpty();
    }

    @Test void collectsSeveralErrorsAtOnce() {
        List<String> e = errors(t("a", UUID.randomUUID(), "nope"), new SubTask("b", "", "", a1, null, null));
        assertThat(e.size()).isGreaterThanOrEqualTo(3);
    }

    private List<SubTask> many(int n) {
        List<SubTask> l = new ArrayList<>();
        for (int i = 0; i < n; i++) l.add(t("k" + i, a1));
        return l;
    }
}
