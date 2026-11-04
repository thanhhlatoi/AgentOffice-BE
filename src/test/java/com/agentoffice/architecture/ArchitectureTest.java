package com.agentoffice.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.assertj.core.api.Assertions.assertThat;

import com.agentoffice.domain.tool.Tool;
import com.agentoffice.entity.task.Task;
import com.agentoffice.service.task.TaskService;
import com.agentoffice.domain.task.TaskStatus;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.EvaluationResult;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.dependencies.SliceAssignment;
import com.tngtech.archunit.library.dependencies.SliceIdentifier;
import jakarta.persistence.Entity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Kiến trúc CHIA THEO TẦNG (docs/ARCHITECTURE.md): controller -> service -> repository/domain/client.
 * - Nhóm BẮT BUỘC: đúng hôm nay (kể cả cho phần đã chuyển sang tầng mới), đỏ nếu ai làm hỏng.
 * - Nhóm CẢNH BÁO: nợ cấu trúc của phần chưa di chuyển; in ra + ghi target/arch-warnings.txt, KHÔNG làm đỏ build.
 *   Mỗi PR di chuyển dọn xong một nhóm thì chuyển rule sang BẮT BUỘC. Bật cứng tất cả: mvn test -Darch.enforce=true
 */
class ArchitectureTest {
    private static JavaClasses classes;
    private static final List<String> warnings = new ArrayList<>();

    /** Lớp "lõi/thuần" phải không biết Spring/JPA (chúng sẽ nằm ở domain/). */
    private static final Set<String> PURE_CORE = Set.of("TaskStateMachine", "PlanValidator", "CanonicalJson", "ScopeValidator",
            "SsrfGuard", "SecretRedactor", "CostCalculator", "ProgressCalculator", "RrfFusion", "TextChunker", "LayoutValidator", "TextNormalizer");

    @BeforeAll
    static void load() {
        classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("com.agentoffice");
    }

    @AfterAll
    static void report() throws IOException {
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/arch-warnings.txt"), String.join("\n\n", warnings));
        if (!warnings.isEmpty()) System.err.println("[ArchUnit] " + warnings.size() + " rule vi phạm (chỉ cảnh báo) – xem target/arch-warnings.txt");
    }

    // ================================================================== BẮT BUỘC
    @Test void onlyTaskServiceChangesTaskStatus() {
        noClasses().that().areNotAssignableTo(TaskService.class).and().areNotAssignableTo(Task.class)
                .should().callMethod(Task.class, "setStatus", TaskStatus.class)
                .because("mọi đổi trạng thái phải qua TaskService.transition -> TaskStateMachine").check(classes);
    }

    /** Mọi cuộc gọi LLM phải đi qua MeteredLlmClient (đo chi phí + ngân sách): kiểu LlmClient thô chỉ được dùng trong client.llm và service.llm. */
    @Test void everyLlmCallGoesThroughMeteredLlmClient() {
        noClasses().that().resideOutsideOfPackages("..client.llm..", "..service.llm..")
                .should().dependOnClassesThat().haveFullyQualifiedName(com.agentoffice.client.llm.LlmClient.class.getName())
                .because("nơi khác phải dùng MeteredLlmClient, nếu không cuộc gọi không được ghi usage/ngân sách").check(classes);
    }

    /** Chỉ CON NGƯỜI (controller) được tăng hạn mức: không agent/tool/runner/service nào gọi BudgetService.raise. */
    @Test void onlyControllersRaiseBudgets() {
        noClasses().that().resideOutsideOfPackages("..controller..")
                .should().callMethod(com.agentoffice.service.usage.BudgetService.class, "raise", java.util.UUID.class, long.class, String.class, String.class)
                .because("ngân sách chỉ do chủ chỉnh qua API; agent không có đường nào").check(classes);
    }

    @Test void agentsAndToolsCannotTouchBudgetAdministration() {
        noClasses().that().resideInAnyPackage("..service.tool..", "..domain.tool..", "..service.approval..")
                .or().implement(com.agentoffice.domain.task.AgentRunner.class)
                .or().implement(com.agentoffice.domain.tool.Tool.class)
                .should().dependOnClassesThat().haveFullyQualifiedName(com.agentoffice.service.usage.BudgetService.class.getName())
                .orShould().dependOnClassesThat().haveFullyQualifiedName(com.agentoffice.controller.usage.BudgetController.class.getName())
                .orShould().dependOnClassesThat().haveFullyQualifiedName(com.agentoffice.repository.usage.BudgetRepository.class.getName())
                .because("agent/tool/runner không được đọc-ghi cấu hình ngân sách").check(classes);
    }

    @Test void controllersAreNotTransactional() {
        noClasses().that().haveSimpleNameEndingWith("Controller")
                .should().beAnnotatedWith(org.springframework.transaction.annotation.Transactional.class)
                .because("@Transactional thuộc lớp service").check(classes);
    }

    @Test void toolsAreOnlyExecutedByTheGateways() {
        noClasses().that().haveSimpleNameNotEndingWith("Gateway").and().haveSimpleNameNotEndingWith("Executor")
                .and().haveSimpleNameNotEndingWith("ApprovalGate")
                .should().callMethod(Tool.class, "execute", com.fasterxml.jackson.databind.JsonNode.class, String.class)
                .because("mọi lời gọi tool đi qua cổng (ToolGateway/ApprovalGate/ToolExecutor)").check(classes);
    }

    /** ArchUnit không thấy chuỗi truy vấn nên quét mã nguồn. */
    @Test void nobodyUpdatesTaskStatusWithRawQueries() throws IOException {
        Pattern p = Pattern.compile("(?is)update\\s+(task|Task)\\b[^;\"]{0,200}?\\bset\\b[^;\"]{0,200}?\\bstatus\\b");
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            List<String> bad = files.filter(f -> f.toString().endsWith(".java")).filter(f -> {
                try { return p.matcher(Files.readString(f)).find(); } catch (IOException e) { throw new RuntimeException(e); }
            }).map(Path::toString).toList();
            assertThat(bad).as("file chứa câu UPDATE task ... status").isEmpty();
        }
    }

    @Test void onlyGoalServiceChangesGoalStatus() {
        noClasses().that().areNotAssignableTo(com.agentoffice.service.task.GoalService.class).and().areNotAssignableTo(com.agentoffice.entity.task.Goal.class)
                .should().callMethod(com.agentoffice.entity.task.Goal.class, "setStatus", com.agentoffice.domain.task.GoalStatus.class)
                .because("mọi đổi trạng thái goal phải qua GoalService.move").check(classes);
    }

    @Test void exceptionUtilAndInfraConfigAreIndependentOfBusinessLayers() {
        noClasses().that().resideInAnyPackage("..exception..", "..util..", "..config.web..", "..config.logging..")
                .should().dependOnClassesThat().resideInAnyPackage("..service..", "..repository..", "..controller..", "..entity..", "..domain..")
                .because("hạ tầng dùng chung không được biết nghiệp vụ").check(classes);
    }

    @Test void domainIsPureJava() {
        noClasses().that().resideInAPackage("..domain..").should().dependOnClassesThat()
                .resideInAnyPackage("org.springframework..", "jakarta.persistence..", "..entity..", "..repository..", "..service..", "..controller..", "..dto..")
                .allowEmptyShould(true).because("domain chỉ có logic thuần, dễ test không cần Spring").check(classes);
    }

    // ================================================================== CẢNH BÁO (nợ cấu trúc)
    @Test void warn_layersOnlyCallDownwards() {
        warn("Luật tầng: controller -> service -> repository (controller không được gọi repository)",
                layeredArchitecture().consideringOnlyDependenciesInAnyPackage("com.agentoffice..").withOptionalLayers(true)
                        .layer("Controller").definedBy("com.agentoffice.controller..")
                        .layer("Service").definedBy("com.agentoffice.service..")
                        .layer("Repository").definedBy("com.agentoffice.repository..")
                        .whereLayer("Controller").mayNotBeAccessedByAnyLayer()
                        .whereLayer("Service").mayOnlyBeAccessedByLayers("Controller")
                        .whereLayer("Repository").mayOnlyBeAccessedByLayers("Service"));
    }

    @Test void warn_controllersDoNotUseRepositories() {
        warn("Controller không import repository",
                noClasses().that().haveSimpleNameEndingWith("Controller").should().dependOnClassesThat().haveSimpleNameEndingWith("Repository"));
    }

    @Test void warn_controllersDoNotExposeEntities() {
        warn("Entity JPA không nằm trong chữ ký public của controller (phải trả dto)",
                methods().that().areDeclaredInClassesThat().haveSimpleNameEndingWith("Controller").and().arePublic().should(notExposeEntities()));
    }

    @Test void warn_pureCoreClassesAreFreeOfSpringAndJpa() {
        warn("Lớp lõi/thuần không dính Spring/JPA (sẽ ở domain/; gắn bean bằng @Bean trong config/)",
                noClasses().that(new com.tngtech.archunit.base.DescribedPredicate<JavaClass>("là lớp lõi/thuần") {
                    @Override public boolean test(JavaClass c) { return PURE_CORE.contains(c.getSimpleName()); }
                }).should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "jakarta.persistence..", "com.agentoffice.config..", "com.agentoffice.common.."));
    }

    @Test void warn_noCyclesBetweenServices() {
        SliceAssignment perService = new SliceAssignment() {
            @Override public SliceIdentifier getIdentifierOf(JavaClass c) {
                String n = c.getSimpleName();
                boolean service = n.endsWith("Service") || n.equals("ApprovalGate") || n.equals("TaskScheduler") || n.equals("TaskDispatcher") || n.equals("ToolGateway");
                return service ? SliceIdentifier.of(n) : SliceIdentifier.ignore();
            }
            @Override public String getDescription() { return "mỗi service là một slice"; }
        };
        warn("Không vòng phụ thuộc giữa các service (A gọi B, B gọi A)", slices().assignedFrom(perService).should().beFreeOfCycles());
    }

    // ================================================================== tiện ích
    private static void warn(String name, ArchRule rule) {
        EvaluationResult r = rule.evaluate(classes);
        if (r.hasViolation()) {
            StringBuilder sb = new StringBuilder("⚠ ").append(name).append(" (").append(r.getFailureReport().getDetails().size()).append(")");
            r.getFailureReport().getDetails().stream().limit(25).forEach(d -> sb.append("\n   - ").append(d));
            warnings.add(sb.toString());
            if (Boolean.getBoolean("arch.enforce")) throw new AssertionError(sb.toString());
        }
    }

    private static ArchCondition<JavaMethod> notExposeEntities() {
        return new ArchCondition<>("không trả về/nhận kiểu @Entity") {
            @Override public void check(JavaMethod m, ConditionEvents events) {
                var involved = new ArrayList<>(m.getReturnType().getAllInvolvedRawTypes());
                m.getParameterTypes().forEach(t -> involved.addAll(t.getAllInvolvedRawTypes()));
                involved.stream().filter(t -> t.isAnnotatedWith(Entity.class)).findFirst().ifPresent(t ->
                        events.add(SimpleConditionEvent.violated(m, m.getFullName() + " lộ entity " + t.getSimpleName())));
            }
        };
    }
}
