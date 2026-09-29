package org.example.retention;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class SeedData {
    public static final String PLAN_ID = "PLAN-DEMO-001";
    public static final String CONFLICT_RESOURCE = "R-CONFLICT";
    public static final String NEW_HOLD_RESOURCE = "R-NEW-HOLD";
    public static final String INTERRUPT_RESOURCE = "R-INTERRUPT";

    private SeedData() {
    }

    public static void seedIfEmpty(RetentionService service) {
        List<Object> resources = Json.list(service.listResources().get("resources"));
        if (!resources.isEmpty()) {
            return;
        }

        service.setClock("2026-09-29T00:00:00Z");

        service.createResource(Map.of(
                "id", CONFLICT_RESOURCE,
                "name", "冲突规则：旧系统日志",
                "category", "log",
                "createdAt", "2024-01-01T00:00:00Z",
                "lastAccessedAt", "2026-01-01T00:00:00Z",
                "tags", List.of("audit", "system"),
                "contentSummary", "sha256:demo-log-summary"
        ), Optional.empty());
        service.createResource(Map.of(
                "id", NEW_HOLD_RESOURCE,
                "name", "冻结后新增保留：合同包",
                "category", "contract",
                "createdAt", "2025-02-10T00:00:00Z",
                "lastAccessedAt", "2026-08-01T00:00:00Z",
                "tags", List.of("contract"),
                "contentSummary", "sha256:demo-contract-summary"
        ), Optional.empty());
        service.createResource(Map.of(
                "id", INTERRUPT_RESOURCE,
                "name", "执行中断恢复：报告",
                "category", "report",
                "createdAt", "2025-03-10T00:00:00Z",
                "lastAccessedAt", "2026-08-10T00:00:00Z",
                "tags", List.of("report"),
                "contentSummary", "sha256:demo-report-summary"
        ), Optional.empty());

        service.createPolicyVersion(Map.of(
                "note", "内置策略：规则优先级冲突、合同与报告保留期",
                "rules", List.of(
                        Map.of(
                                "ruleId", "RULE-LOG-SPECIFIC",
                                "category", "log",
                                "priority", 1,
                                "retentionDays", 90,
                                "requiredTags", List.of("system")
                        ),
                        Map.of(
                                "ruleId", "RULE-LOG-AUDIT-STRICT",
                                "category", "log",
                                "priority", 2,
                                "retentionDays", 365,
                                "requiredTags", List.of("audit")
                        ),
                        Map.of(
                                "ruleId", "RULE-CONTRACT-DEFAULT",
                                "category", "contract",
                                "priority", 10,
                                "retentionDays", 180
                        ),
                        Map.of(
                                "ruleId", "RULE-REPORT-DEFAULT",
                                "category", "report",
                                "priority", 10,
                                "retentionDays", 180
                        )
                )
        ), Optional.of("2026-09-01T00:00:00Z"));

        service.createPlan(Map.of(
                "id", PLAN_ID,
                "note", "预置可重放删除计划：冻结后允许新增保留与模拟中断",
                "frozenAt", "2026-09-20T00:00:00Z"
        ), Optional.of("2026-09-20T00:00:00Z"));

        service.addHold(NEW_HOLD_RESOURCE, Map.of(
                "eventType", "HOLD",
                "effectiveFrom", "2026-09-25T00:00:00Z",
                "reason", "计划冻结后出现的新诉讼保留；解除只能追加 RELEASE 事件"
        ), Optional.of("2026-09-25T09:00:00Z"));

        service.approvePlan(PLAN_ID, Map.of(
                "operator", "alice",
                "expectedPlanVersion", 1,
                "idempotencyKey", "demo-approval-generation"
        ));
        service.runPreExecutionChecks(PLAN_ID, Map.of(
                "idempotencyKey", "demo-precheck",
                "runAt", "2026-09-29T00:00:00Z"
        ), Optional.empty());
        service.runExecution(PLAN_ID, Map.of(
                "idempotencyKey", "demo-run-first-item",
                "runAt", "2026-09-29T00:00:00Z",
                "itemIds", List.of(CONFLICT_RESOURCE)
        ), Optional.empty());
        try {
            service.runExecution(PLAN_ID, Map.of(
                    "idempotencyKey", "demo-run-interrupted",
                    "runAt", "2026-09-29T00:00:00Z",
                    "itemIds", List.of(INTERRUPT_RESOURCE),
                    "crashAfterItemId", INTERRUPT_RESOURCE
            ), Optional.empty());
        } catch (RetentionService.SimulatedExitException ignored) {
            // Seed intentionally leaves the second execution's item UNKNOWN until verified.
        }
    }
}
