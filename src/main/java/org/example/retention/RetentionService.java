package org.example.retention;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class RetentionService {
    private final Database db;
    private final Clock clock;

    public RetentionService(Database database, Clock clock) {
        this.db = database;
        this.clock = clock;
    }

    public Map<String, Object> clockStatus() {
        return response("clock", Map.of("now", clock.now()));
    }

    public Map<String, Object> setClock(String instant) {
        return response("clock", Map.of("now", clock.set(instant)));
    }

    public Map<String, Object> advanceClock(String duration) {
        return response("clock", Map.of("now", clock.advance(duration)));
    }

    public Map<String, Object> createResource(Map<String, Object> body, Optional<String> at) {
        String time = at.orElseGet(clock::now);
        String id = require(body, "id");
        requireText(body, "name");
        requireText(body, "category");
        requireText(body, "contentSummary");
        String createdAt = Clock.normalize(str(body, "createdAt", time));
        String lastAccessedAt = Clock.normalize(str(body, "lastAccessedAt", createdAt));
        if (Instant.parse(lastAccessedAt).isBefore(Instant.parse(createdAt))) {
            throw new ApiException(400, "lastAccessedAt cannot be earlier than createdAt");
        }
        Map<String, Object> snapshot = baseResourceSnapshot(id, body, createdAt, lastAccessedAt);
        snapshot.put("version", 1);
        String canonical = Json.write(snapshot);
        String fingerprint = Hashes.sha256(canonical);
        snapshot.put("fingerprint", fingerprint);
        String versionId = "RV-" + randomId();
        db.transaction(() -> {
            try {
                PreparedStatement resource = db.connection().prepareStatement("""
                    INSERT INTO resources(id,name,category,created_at,last_accessed_at,tags_json,content_summary,current_version,is_deleted,created_at_record)
                    VALUES (?,?,?,?,?,?,?,1,0,?)
                    """);
                resource.setString(1, id);
                resource.setString(2, (String) snapshot.get("name"));
                resource.setString(3, (String) snapshot.get("category"));
                resource.setString(4, createdAt);
                resource.setString(5, lastAccessedAt);
                resource.setString(6, Json.write(snapshot.get("tags")));
                resource.setString(7, (String) snapshot.get("contentSummary"));
                resource.setString(8, time);
                resource.executeUpdate();

                PreparedStatement version = db.connection().prepareStatement("""
                    INSERT INTO resource_versions(id,resource_id,version,name,category,created_at,last_accessed_at,tags_json,content_summary,fingerprint,snapshot_json,recorded_at)
                    VALUES (?, ?, 1, ?,?,?,?,?,?,?,?,?)
                    """);
                version.setString(1, versionId);
                version.setString(2, id);
                version.setString(3, (String) snapshot.get("name"));
                version.setString(4, (String) snapshot.get("category"));
                version.setString(5, createdAt);
                version.setString(6, lastAccessedAt);
                version.setString(7, Json.write(snapshot.get("tags")));
                version.setString(8, (String) snapshot.get("contentSummary"));
                version.setString(9, fingerprint);
                version.setString(10, Json.write(snapshot));
                version.setString(11, time);
                version.executeUpdate();
            } catch (SQLException exception) {
                if (unique(exception)) {
                    throw new ApiException(409, "resource already exists: " + id);
                }
                throw new IllegalStateException("Cannot create resource", exception);
            }
        });
        return getResource(id);
    }

    public Map<String, Object> updateResource(String resourceId, Map<String, Object> body, Optional<String> at) {
        String time = at.orElseGet(clock::now);
        Map<String, Object> current = requireResource(resourceId, true);
        int version = intValue(current.get("currentVersion")) + 1;
        String createdAt = str(current, "createdAt", null);
        String lastAccessedAt = Clock.normalize(str(body, "lastAccessedAt", str(current, "lastAccessedAt", null)));
        Map<String, Object> snapshot = baseResourceSnapshot(resourceId, merged(body, current), createdAt, lastAccessedAt);
        snapshot.put("version", version);
        String fingerprint = Hashes.sha256(Json.write(snapshot));
        snapshot.put("fingerprint", fingerprint);
        String versionId = "RV-" + randomId();
        db.transaction(() -> {
            try {
                PreparedStatement update = db.connection().prepareStatement("""
                    UPDATE resources SET name=?, category=?, last_accessed_at=?, tags_json=?, content_summary=?, current_version=?
                    WHERE id=? AND is_deleted=0
                    """);
                update.setString(1, (String) snapshot.get("name"));
                update.setString(2, (String) snapshot.get("category"));
                update.setString(3, lastAccessedAt);
                update.setString(4, Json.write(snapshot.get("tags")));
                update.setString(5, (String) snapshot.get("contentSummary"));
                update.setInt(6, version);
                update.setString(7, resourceId);
                if (update.executeUpdate() != 1) {
                    throw new ApiException(409, "deleted resource cannot be updated");
                }
                PreparedStatement insertVersion = db.connection().prepareStatement("""
                    INSERT INTO resource_versions(id,resource_id,version,name,category,created_at,last_accessed_at,tags_json,content_summary,fingerprint,snapshot_json,recorded_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                    """);
                insertVersion.setString(1, versionId);
                insertVersion.setString(2, resourceId);
                insertVersion.setInt(3, version);
                insertVersion.setString(4, (String) snapshot.get("name"));
                insertVersion.setString(5, (String) snapshot.get("category"));
                insertVersion.setString(6, createdAt);
                insertVersion.setString(7, lastAccessedAt);
                insertVersion.setString(8, Json.write(snapshot.get("tags")));
                insertVersion.setString(9, (String) snapshot.get("contentSummary"));
                insertVersion.setString(10, fingerprint);
                insertVersion.setString(11, Json.write(snapshot));
                insertVersion.setString(12, time);
                insertVersion.executeUpdate();
            } catch (SQLException exception) {
                throw new IllegalStateException("Cannot update resource", exception);
            }
        });
        return getResource(resourceId);
    }

    public Map<String, Object> listResources() {
        Json.JArray array = new Json.JArray();
        try (PreparedStatement statement = db.connection().prepareStatement(
                "SELECT * FROM resources ORDER BY id");
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                array.add(resourceFromRow(resultSet));
            }
            return response("resources", array);
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot list resources", exception);
        }
    }

    public Map<String, Object> getResource(String resourceId) {
        return response("resource", requireResource(resourceId, false));
    }

    public Map<String, Object> createPolicyVersion(Map<String, Object> body, Optional<String> at) {
        String time = at.orElseGet(clock::now);
        List<Map<String, Object>> rules = rulesFromBody(body.get("rules"));
        for (Map<String, Object> rule : rules) {
            validateRule(rule);
        }
        rules.sort(Comparator
                .comparingInt((Map<String, Object> rule) -> intValue(rule.get("priority")))
                .thenComparing(rule -> String.valueOf(rule.get("ruleId"))));
        int version = nextPolicyVersion() + 1;
        String id = "PV-" + version;
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", id);
        snapshot.put("version", version);
        snapshot.put("note", str(body, "note", "策略版本 " + version));
        snapshot.put("rules", rules);
        snapshot.put("createdAt", time);
        String fingerprint = Hashes.sha256(Json.write(snapshot));
        snapshot.put("fingerprint", fingerprint);
        db.transaction(() -> {
            try {
                PreparedStatement statement = db.connection().prepareStatement("""
                    INSERT INTO policy_versions(id,version,note,snapshot_json,fingerprint,created_at)
                    VALUES (?,?,?,?,?,?)
                    """);
                statement.setString(1, id);
                statement.setInt(2, version);
                statement.setString(3, str(body, "note", "策略版本 " + version));
                statement.setString(4, Json.write(snapshot));
                statement.setString(5, fingerprint);
                statement.setString(6, time);
                statement.executeUpdate();
            } catch (SQLException exception) {
                throw new IllegalStateException("Cannot create policy", exception);
            }
        });
        return getPolicyVersion(id);
    }

    public Map<String, Object> listPolicies() {
        Json.JArray array = new Json.JArray();
        try {
            ResultSet resultSet = db.connection().createStatement().executeQuery(
                    "SELECT snapshot_json FROM policy_versions ORDER BY version DESC");
            while (resultSet.next()) {
                array.add(Json.parse(resultSet.getString(1)));
            }
            return response("policyVersions", array);
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot list policies", exception);
        }
    }

    public Map<String, Object> getPolicyVersion(String policyId) {
        return response("policyVersion", requirePolicy(policyId));
    }

    public Map<String, Object> addHold(String resourceId, Map<String, Object> body, Optional<String> at) {
        requireResource(resourceId, false);
        String eventType = require(body, "eventType").toUpperCase(Locale.ROOT);
        if (!eventType.equals("HOLD") && !eventType.equals("RELEASE")) {
            throw new ApiException(400, "eventType must be HOLD or RELEASE");
        }
        String createdAt = Clock.normalize(str(body, "createdAt", at.orElseGet(clock::now)));
        String effectiveFrom = Clock.normalize(str(body, "effectiveFrom", createdAt));
        String effectiveTo = body.get("effectiveTo") == null ? null : Clock.normalize(str(body, "effectiveTo", null));
        String targetHoldId = str(body, "targetHoldId", null);
        if (effectiveTo != null && Instant.parse(effectiveTo).isBefore(Instant.parse(effectiveFrom))) {
            throw new ApiException(400, "effectiveTo cannot be earlier than effectiveFrom");
        }
        requireText(body, "reason");
        String id = "LH-" + randomId();
        db.transaction(() -> {
            try {
                PreparedStatement statement = db.connection().prepareStatement("""
                    INSERT INTO legal_holds(id,resource_id,event_type,effective_from,effective_to,target_hold_id,reason,created_at)
                    VALUES (?,?,?,?,?,?,?,?)
                    """);
                statement.setString(1, id);
                statement.setString(2, resourceId);
                statement.setString(3, eventType);
                statement.setString(4, effectiveFrom);
                statement.setString(5, effectiveTo);
                statement.setString(6, targetHoldId);
                statement.setString(7, str(body, "reason", null));
                statement.setString(8, createdAt);
                statement.executeUpdate();
            } catch (SQLException exception) {
                throw new IllegalStateException("Cannot append legal hold", exception);
            }
        });
        return response("hold", holdById(id));
    }

    public Map<String, Object> listHolds(String resourceId) {
        Json.JArray array = new Json.JArray();
        try {
            PreparedStatement statement = db.connection().prepareStatement(
                    "SELECT * FROM legal_holds WHERE (? IS NULL OR resource_id = ?) ORDER BY effective_from, created_at, id");
            if (resourceId == null || resourceId.isBlank()) {
                statement.setNull(1, java.sql.Types.VARCHAR);
                statement.setNull(2, java.sql.Types.VARCHAR);
            } else {
                statement.setString(1, resourceId);
                statement.setString(2, resourceId);
            }
            ResultSet resultSet = statement.executeQuery();
            while (resultSet.next()) {
                array.add(holdFromRow(resultSet));
            }
            return response("holds", array);
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot list holds", exception);
        }
    }

    public Map<String, Object> createPlan(Map<String, Object> body, Optional<String> at) {
        String frozenAt = Clock.normalize(str(body, "frozenAt", at.orElseGet(clock::now)));
        Map<String, Object> policy;
        String policyId = str(body, "policyVersionId", null);
        policy = policyId == null ? latestPolicy().orElseThrow(() -> new ApiException(400, "no policy version exists")) : requirePolicy(policyId);
        List<Map<String, Object>> resources = allResourceMaps(false);
        String planId = str(body, "id", "PLAN-" + randomId());
        String note = str(body, "note", "冻结计划");
        List<Map<String, Object>> items = new ArrayList<>();
        db.transaction(() -> {
            try {
                PreparedStatement planStatement = db.connection().prepareStatement("""
                    INSERT INTO plans(id,version,frozen_at,policy_version_id,policy_version_number,policy_fingerprint,status,note,created_at)
                    VALUES (?, 1, ?, ?, ?, ?, 'FROZEN', ?, ?)
                    """);
                planStatement.setString(1, planId);
                planStatement.setString(2, frozenAt);
                planStatement.setString(3, (String) policy.get("id"));
                planStatement.setInt(4, intValue(policy.get("version")));
                planStatement.setString(5, (String) policy.get("fingerprint"));
                planStatement.setString(6, note);
                planStatement.setString(7, clock.now());
                planStatement.executeUpdate();

                int seq = 1;
                for (Map<String, Object> resource : resources) {
                    Map<String, Object> version = resourceVersion(resource.get("id").toString(), intValue(resource.get("currentVersion")));
                    Evaluation evaluation = evaluate(resource, policy, frozenAt);
                    String itemId = "PI-" + randomId();
                    String status = evaluation.disposition().equals("DELETABLE") ? "PENDING" : "RETAINED";
                    insertPlanItem(itemId, planId, seq++, resource, version, evaluation, status);
                    items.add(planItemMap(itemId, planId, seq - 1, resource.get("id").toString(), version.get("versionId").toString(),
                            (String) version.get("fingerprint"), evaluation.disposition(), status, evaluation.explanation(), null));
                }
            } catch (SQLException exception) {
                if (unique(exception)) {
                    throw new ApiException(409, "plan already exists: " + planId);
                }
                throw new IllegalStateException("Cannot freeze plan", exception);
            }
        });
        return getPlan(planId);
    }

    public Map<String, Object> listPlans() {
        Json.JArray array = new Json.JArray();
        try {
            ResultSet resultSet = db.connection().createStatement().executeQuery("SELECT id FROM plans ORDER BY frozen_at DESC, rowid DESC");
            while (resultSet.next()) {
                array.add(summary("plans", "id", resultSet.getString(1)));
            }
            return response("plans", array);
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot list plans", exception);
        }
    }

    public Map<String, Object> getPlan(String planId) {
        return response("plan", planSummary(planId));
    }

    public Map<String, Object> approvePlan(String planId, Map<String, Object> body) {
        int expected = body.containsKey("expectedPlanVersion") ? intValue(body.get("expectedPlanVersion")) : 1;
        String operator = str(body, "operator", "operator");
        String key = require(body, "idempotencyKey");
        return db.transaction(() -> {
            Map<String, Object> plan = planRow(planId);
            int planVersion = intValue(plan.get("version"));
            if (planVersion != expected) {
                throw new ApiException(409, "stale plan version: expected " + expected + ", current " + planVersion);
            }
            try {
                PreparedStatement existing = db.connection().prepareStatement("SELECT id, idempotency_key FROM executions WHERE plan_id=? OR idempotency_key=?");
                existing.setString(1, planId);
                existing.setString(2, key);
                ResultSet resultSet = existing.executeQuery();
                if (resultSet.next()) {
                    String existingId = resultSet.getString(1);
                    if (key.equals(resultSet.getString(2))) {
                        return response("execution", executionSummary(existingId));
                    }
                    throw new ApiException(409, "plan already has an execution generation");
                }

                String currentStatus = String.valueOf(plan.get("status"));
                if (!currentStatus.equals("FROZEN")) {
                    throw new ApiException(409, "plan is not awaiting approval");
                }
                String executionId = "EX-" + randomId();
                String now = clock.now();
                PreparedStatement updatePlan = db.connection().prepareStatement("""
                    UPDATE plans SET status='APPROVED', version=version+1
                    WHERE id=? AND version=? AND status='FROZEN'
                    """);
                updatePlan.setString(1, planId);
                updatePlan.setInt(2, expected);
                if (updatePlan.executeUpdate() != 1) {
                    throw new ApiException(409, "stale plan version or plan already approved");
                }
                PreparedStatement insert = db.connection().prepareStatement("""
                    INSERT INTO executions(id,plan_id,generation,status,operator,idempotency_key,expected_plan_version,created_at,updated_at)
                    VALUES (?,?,1,'PENDING',?,?,?, ?,?)
                    """);
                insert.setString(1, executionId);
                insert.setString(2, planId);
                insert.setString(3, operator);
                insert.setString(4, key);
                insert.setInt(5, expected);
                insert.setString(6, now);
                insert.setString(7, now);
                insert.executeUpdate();
                insertEvent(executionId, null, null, "GENERATION_CREATED", Map.of("operator", operator, "expectedPlanVersion", expected));
                return response("execution", executionSummary(executionId));
            } catch (SQLException exception) {
                if (unique(exception)) {
                    throw new ApiException(409, "concurrent approval created one execution generation");
                }
                throw new IllegalStateException("Cannot approve plan", exception);
            }
        });
    }

    public Map<String, Object> runPreExecutionChecks(String planId, Map<String, Object> body, Optional<String> at) {
        String runAt = Clock.normalize(str(body, "runAt", at.orElseGet(clock::now)));
        String requestToken = str(body, "idempotencyKey", "PRECHECK-" + randomId());
        for (Map<String, Object> item : planItemRows(planId)) {
            if (!"PENDING".equals(item.get("status"))) {
                continue;
            }
            String resourceId = item.get("resourceId").toString();
            String itemId = item.get("id").toString();
            Optional<Map<String, Object>> hold = activeHold(resourceId, runAt);
            if (hold.isPresent()) {
                blockByCurrentCheck(planId, itemId, resourceId, hold.get(), requestToken, runAt,
                        "执行前法律保留检查发现新出现的有效保留；冻结解释保持不变，仅阻止执行");
                continue;
            }
            String frozenFingerprint = item.get("resourceFingerprint").toString();
            String currentFingerprint = currentFingerprint(resourceId);
            if (!frozenFingerprint.equals(currentFingerprint)) {
                Map<String, Object> reason = new LinkedHashMap<>();
                reason.put("frozenFingerprint", frozenFingerprint);
                reason.put("currentFingerprint", currentFingerprint);
                reason.put("message", "冻结后资源快照发生变化，执行前检查阻止该条目");
                blockByCurrentCheck(planId, itemId, resourceId, reason, requestToken, runAt, Json.write(reason));
            }
        }
        return getPlan(planId);
    }

    public Map<String, Object> runExecution(String planId, Map<String, Object> body, Optional<String> at) {
        String requestToken = require(body, "idempotencyKey");
        Integer crashAfterSeq = body.containsKey("crashAfterSeq") ? intValue(body.get("crashAfterSeq")) : null;
        String runAt = Clock.normalize(str(body, "runAt", at.orElseGet(clock::now)));
        Map<String, Object> execution = executionByPlan(planId)
                .orElseThrow(() -> new ApiException(409, "plan has no approved execution generation"));
        if (requestToken.equals(execution.get("idempotencyKey")) && countUnknownItems(planId) == 0) {
            return response("execution", execution);
        }
        if (countUnknownItems(planId) > 0) {
            throw new ApiException(409, "unconfirmed results must be verified before resuming");
        }
        Set<String> selectedItems = selectedItems(body.get("itemIds"));
        String crashAfterItemId = str(body, "crashAfterItemId", null);
        return processApprovedPlan(planId, requestToken, crashAfterSeq, crashAfterItemId, selectedItems, runAt);
    }

    public Map<String, Object> getExecution(String executionId) {
        return response("execution", executionSummary(executionId));
    }

    public Map<String, Object> verifyItem(String itemId, Map<String, Object> body) {
        String requestToken = str(body, "requestToken", "VERIFY-" + randomId());
        Map<String, Object> item = planItemRow(itemId);
        if (!"UNKNOWN".equals(item.get("status"))) {
            return response("verification", Map.of(
                    "planItemId", itemId,
                    "status", item.get("status"),
                    "requestToken", requestToken,
                    "decision", "NO_UNKNOWN_RESULT"));
        }
        boolean deleted = ledgerExistsByItem(itemId);
        db.transaction(() -> {
            try {
                if (deleted) {
                    PreparedStatement receiptUpdate = db.connection().prepareStatement("""
                        UPDATE execution_receipts SET status='DELETED', completed_at=?
                        WHERE plan_item_id=? AND status='PROCESSING'
                        """);
                    receiptUpdate.setString(1, clock.now());
                    receiptUpdate.setString(2, itemId);
                    receiptUpdate.executeUpdate();
                    PreparedStatement itemUpdate = db.connection().prepareStatement("UPDATE plan_items SET status='DELETED' WHERE id=? AND status='UNKNOWN'");
                    itemUpdate.setString(1, itemId);
                    itemUpdate.executeUpdate();
                } else {
                    PreparedStatement receiptUpdate = db.connection().prepareStatement("""
                        UPDATE execution_receipts SET status='NOT_CONFIRMED', completed_at=?
                        WHERE plan_item_id=? AND status='PROCESSING'
                        """);
                    receiptUpdate.setString(1, clock.now());
                    receiptUpdate.setString(2, itemId);
                    receiptUpdate.executeUpdate();
                    PreparedStatement itemUpdate = db.connection().prepareStatement("UPDATE plan_items SET status='PENDING' WHERE id=? AND status='UNKNOWN'");
                    itemUpdate.setString(1, itemId);
                    itemUpdate.executeUpdate();
                }
            } catch (SQLException exception) {
                throw new IllegalStateException("Cannot verify item", exception);
            }
        });
        Map<String, Object> refreshed = planItemRow(itemId);
        String planId = refreshed.get("planId").toString();
        boolean unresolved = planItemRows(planId).stream()
                .anyMatch(planItem -> "PENDING".equals(planItem.get("status")) || "UNKNOWN".equals(planItem.get("status")));
        String executionId = executionIdByPlan(planId);
        if (unresolved) {
            updateExecutionStatus(executionId, "INTERRUPTED", null);
            updatePlanStatus(planId, "APPROVED");
        } else {
            updateExecutionStatus(executionId, "COMPLETED", clock.now());
            updatePlanStatus(planId, "COMPLETED");
        }
        insertEvent(executionId, itemId, intValue(refreshed.get("seq")), deleted ? "VERIFIED_DELETED" : "VERIFIED_NOT_DELETED",
                Map.of("requestToken", requestToken, "ledgerRecordFound", deleted));
        return response("verification", Map.of(
                "planItemId", itemId,
                "requestToken", requestToken,
                "ledgerRecordFound", deleted,
                "decision", deleted ? "CONFIRMED_DELETED" : "SAFE_TO_DECIDE_LATER",
                "itemStatus", refreshed.get("status")));
    }

    private Map<String, Object> processApprovedPlan(String planId, String requestToken, Integer crashAfterSeq,
                                                    String crashAfterItemId, Set<String> selectedItems, String runAt) {
        updateExecutionStatus(executionIdByPlan(planId), "RUNNING", null);
        List<Map<String, Object>> items = planItemRows(planId);
        for (Map<String, Object> item : items) {
            String status = String.valueOf(item.get("status"));
            int seq = intValue(item.get("seq"));
            String itemId = item.get("id").toString();
            if (!status.equals("PENDING")) {
                continue;
            }
            String resourceId = item.get("resourceId").toString();
            if (!selectedItems.isEmpty() && !selectedItems.contains(resourceId)) {
                continue;
            }
            Map<String, Object> resource = requireResource(resourceId, false);
            Optional<Map<String, Object>> hold = activeHold(resourceId, runAt);
            if (hold.isPresent()) {
                blockByCurrentCheck(planId, itemId, resourceId, hold.get(), requestToken, runAt,
                        "执行前法律保留检查发现有效保留事件；冻结解释不重写，仅阻止本条目");
                continue;
            }
            String frozenFingerprint = String.valueOf(item.get("resourceFingerprint"));
            String currentFingerprint = currentFingerprint(resourceId);
            if (!frozenFingerprint.equals(currentFingerprint)) {
                Map<String, Object> reason = new LinkedHashMap<>();
                reason.put("frozenFingerprint", frozenFingerprint);
                reason.put("currentFingerprint", currentFingerprint);
                reason.put("message", "冻结后资源快照发生变化，执行器拒绝在旧快照上删除");
                blockByCurrentCheck(planId, itemId, resourceId, reason, requestToken, runAt, Json.write(reason));
                continue;
            }
            beginUncertainDeletion(planId, itemId, resourceId, frozenFingerprint, requestToken, seq, runAt);
            if ((crashAfterSeq != null && crashAfterSeq == seq) || resourceId.equals(crashAfterItemId)) {
                String executionId = executionIdByPlan(planId);
                markInterrupted(executionId);
                throw new SimulatedExitException(503,
                        "simulated executor exit after item seq " + seq + "; result is UNKNOWN until verified", executionId);
            }
            confirmDeletion(planId, itemId, resourceId, frozenFingerprint, requestToken, runAt);
        }
        boolean unresolved = planItemRows(planId).stream()
                .anyMatch(item -> "PENDING".equals(item.get("status")) || "UNKNOWN".equals(item.get("status")));
        if (!unresolved) {
            updateExecutionStatus(executionIdByPlan(planId), "COMPLETED", clock.now());
        }
        updatePlanStatus(planId, unresolved ? "APPROVED" : "COMPLETED");
        return response("execution", executionSummary(executionIdByPlan(planId)));
    }

    private void beginUncertainDeletion(String planId, String itemId, String resourceId, String fingerprint,
                                        String requestToken, int seq, String runAt) {
        db.transaction(() -> {
            try {
                PreparedStatement itemUpdate = db.connection().prepareStatement(
                        "UPDATE plan_items SET status='UNKNOWN' WHERE id=? AND status='PENDING'");
                itemUpdate.setString(1, itemId);
                if (itemUpdate.executeUpdate() != 1) {
                    throw new ApiException(409, "item no longer pending: " + itemId);
                }
                String token = itemToken(itemId, requestToken);
                PreparedStatement existingReceipt = db.connection().prepareStatement(
                        "SELECT id, status FROM execution_receipts WHERE item_token=?");
                existingReceipt.setString(1, token);
                ResultSet existingReceiptResult = existingReceipt.executeQuery();
                if (existingReceiptResult.next()) {
                    String receiptStatus = existingReceiptResult.getString(2);
                    if (!"NOT_CONFIRMED".equals(receiptStatus)) {
                        throw new ApiException(409, "item token already resolved as " + receiptStatus);
                    }
                    PreparedStatement receiptReset = db.connection().prepareStatement("""
                        UPDATE execution_receipts
                        SET status='PROCESSING', reason=NULL, request_token=?, started_at=?, completed_at=NULL
                        WHERE item_token=?
                        """);
                    receiptReset.setString(1, requestToken);
                    receiptReset.setString(2, runAt);
                    receiptReset.setString(3, token);
                    receiptReset.executeUpdate();
                } else {
                    PreparedStatement receiptInsert = db.connection().prepareStatement("""
                        INSERT INTO execution_receipts(id,execution_id,plan_item_id,seq,item_token,status,request_token,started_at)
                        VALUES (?,?,?,?,?,'PROCESSING',?,?)
                        """);
                    receiptInsert.setString(1, "RC-" + randomId());
                    receiptInsert.setString(2, executionIdByPlan(planId));
                    receiptInsert.setString(3, itemId);
                    receiptInsert.setInt(4, seq);
                    receiptInsert.setString(5, token);
                    receiptInsert.setString(6, requestToken);
                    receiptInsert.setString(7, runAt);
                    receiptInsert.executeUpdate();
                }
            } catch (SQLException exception) {
                if (unique(exception)) {
                    throw new ApiException(409, "idempotent item token already used");
                }
                throw new IllegalStateException("Cannot begin uncertain deletion", exception);
            }
        });
    }

    private void confirmDeletion(String planId, String itemId, String resourceId, String fingerprint,
                                 String requestToken, String runAt) {
        String executionId = executionIdByPlan(planId);
        String receiptId = receiptId(executionId, itemId);
        db.transaction(() -> {
            try {
                PreparedStatement ledger = db.connection().prepareStatement("""
                    INSERT INTO delete_ledger(id,resource_id,plan_item_id,execution_id,receipt_id,resource_fingerprint,deleted_at)
                    VALUES (?,?,?,?,?,?,?)
                    """);
                ledger.setString(1, "DL-" + randomId());
                ledger.setString(2, resourceId);
                ledger.setString(3, itemId);
                ledger.setString(4, executionId);
                ledger.setString(5, receiptId);
                ledger.setString(6, fingerprint);
                ledger.setString(7, runAt);
                ledger.executeUpdate();
                PreparedStatement resourceUpdate = db.connection().prepareStatement("UPDATE resources SET is_deleted=1 WHERE id=?");
                resourceUpdate.setString(1, resourceId);
                resourceUpdate.executeUpdate();
                PreparedStatement receipt = db.connection().prepareStatement(
                        "UPDATE execution_receipts SET status='DELETED', completed_at=? WHERE id=? AND status='PROCESSING'");
                receipt.setString(1, runAt);
                receipt.setString(2, receiptId);
                receipt.executeUpdate();
                PreparedStatement item = db.connection().prepareStatement(
                        "UPDATE plan_items SET status='DELETED' WHERE id=? AND status='UNKNOWN'");
                item.setString(1, itemId);
                item.executeUpdate();
            } catch (SQLException exception) {
                if (unique(exception)) {
                    throw new ApiException(409, "resource already has a deletion ledger record");
                }
                throw new IllegalStateException("Cannot confirm deletion", exception);
            }
        });
    }

    private void blockByCurrentCheck(String planId, String itemId, String resourceId, Object reasonObject,
                                     String requestToken, String runAt, String reason) {
        String executionId = executionIdByPlan(planId);
        db.transaction(() -> {
            try {
                PreparedStatement itemUpdate = db.connection().prepareStatement(
                        "UPDATE plan_items SET status='BLOCKED', blocked_reason=? WHERE id=? AND status='PENDING'");
                itemUpdate.setString(1, reason);
                itemUpdate.setString(2, itemId);
                itemUpdate.executeUpdate();
                PreparedStatement receipt = db.connection().prepareStatement("""
                    INSERT INTO execution_receipts(id,execution_id,plan_item_id,seq,item_token,status,reason,request_token,started_at,completed_at)
                    VALUES (?,?,?,?,?,'BLOCKED',?,?,?,?)
                    """);
                int seq = intValue(planItemRow(itemId).get("seq"));
                receipt.setString(1, "RC-" + randomId());
                receipt.setString(2, executionId);
                receipt.setString(3, itemId);
                receipt.setInt(4, seq);
                receipt.setString(5, itemToken(itemId, requestToken));
                receipt.setString(6, reason);
                receipt.setString(7, requestToken);
                receipt.setString(8, runAt);
                receipt.setString(9, runAt);
                receipt.executeUpdate();
            } catch (SQLException exception) {
                throw new IllegalStateException("Cannot block item", exception);
            }
        });
        insertEventForItem(itemId, "BLOCKED_CURRENT_HOLD_OR_DRIFT", reasonObject instanceof Map<?, ?> map
                ? castObjectMap(map) : Map.of("reason", String.valueOf(reasonObject)));
    }

    public Map<String, Object> traceResource(String resourceId) {
        Map<String, Object> resource = requireResource(resourceId, false);
        Json.JArray versions = new Json.JArray();
        Json.JArray holds = new Json.JArray();
        Json.JArray planItems = new Json.JArray();
        Json.JArray receipts = new Json.JArray();
        try {
            PreparedStatement versionStatement = db.connection().prepareStatement(
                    "SELECT snapshot_json FROM resource_versions WHERE resource_id=? ORDER BY version");
            versionStatement.setString(1, resourceId);
            ResultSet versionResult = versionStatement.executeQuery();
            while (versionResult.next()) {
                versions.add(Json.parse(versionResult.getString(1)));
            }

            PreparedStatement holdStatement = db.connection().prepareStatement(
                    "SELECT * FROM legal_holds WHERE resource_id=? ORDER BY effective_from, created_at, id");
            holdStatement.setString(1, resourceId);
            ResultSet holdResult = holdStatement.executeQuery();
            while (holdResult.next()) {
                holds.add(holdFromRow(holdResult));
            }

            PreparedStatement itemStatement = db.connection().prepareStatement("""
                SELECT pi.*, p.id AS plan_ref, p.frozen_at, p.policy_version_id
                FROM plan_items pi JOIN plans p ON p.id = pi.plan_id
                WHERE pi.resource_id=? ORDER BY p.frozen_at, pi.seq
                """);
            itemStatement.setString(1, resourceId);
            ResultSet itemResult = itemStatement.executeQuery();
            while (itemResult.next()) {
                planItems.add(planItemFromRow(itemResult));
            }

            PreparedStatement receiptStatement = db.connection().prepareStatement("""
                SELECT er.* FROM execution_receipts er
                JOIN plan_items pi ON pi.id = er.plan_item_id
                WHERE pi.resource_id=? ORDER BY er.started_at, er.seq
                """);
            receiptStatement.setString(1, resourceId);
            ResultSet receiptResult = receiptStatement.executeQuery();
            while (receiptResult.next()) {
                receipts.add(receiptFromRow(receiptResult));
            }

            Json.JObject result = new Json.JObject();
            result.put("resource", resource);
            result.put("versions", versions);
            result.put("legalHolds", holds);
            result.put("planItems", planItems);
            result.put("receipts", receipts);
            result.put("activeHoldsAtCurrentClock", activeHolds(resourceId, clock.now()));
            result.put("why", why(resourceId));
            return response("trace", result);
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot trace resource", exception);
        }
    }

    private Map<String, Object> why(String resourceId) {
        Optional<Map<String, Object>> policy = latestPolicy();
        Map<String, Object> resource = requireResource(resourceId, false);
        if (policy.isEmpty()) {
            return Map.of("decision", "RETAIN_NO_RULE", "message", "没有任何策略版本，当前保留");
        }
        Evaluation evaluation = evaluate(resource, policy.get(), clock.now());
        return Map.of("decision", evaluation.disposition(), "explanation", evaluation.explanation());
    }

    private Evaluation evaluate(Map<String, Object> resource, Map<String, Object> policy, String at) {
        String resourceId = resource.get("id").toString();
        long ageDays = Clock.daysBetween(String.valueOf(resource.get("createdAt")), at);
        long accessAgeDays = Clock.daysBetween(String.valueOf(resource.get("lastAccessedAt")), at);
        Json.JArray matched = new Json.JArray();
        Json.JArray considered = new Json.JArray();
        List<Map<String, Object>> matchedRules = new ArrayList<>();
        for (Object item : Json.list(policy.get("rules"))) {
            Map<String, Object> rule = castObject(item);
            Json.JObject conditionResult = new Json.JObject();
            conditionResult.put("ruleId", rule.get("ruleId"));
            conditionResult.put("priority", rule.get("priority"));
            conditionResult.put("retentionDays", rule.get("retentionDays"));
            boolean matches = matchesRule(resource, rule, ageDays, accessAgeDays, conditionResult);
            considered.add(conditionResult);
            if (matches) {
                matched.add(rule);
                matchedRules.add(rule);
            }
        }
        Optional<Map<String, Object>> hold = activeHold(resourceId, at);
        if (hold.isPresent()) {
            return new Evaluation("RETAIN_HOLD", explanation(resource, at, ageDays, accessAgeDays, matched, considered, null, null,
                    "法律保留事件在当前时刻有效，必须保留，保留优先于删除资格", hold.get(), null));
        }
        if (matchedRules.isEmpty()) {
            return new Evaluation("RETAIN_NO_RULE", explanation(resource, at, ageDays, accessAgeDays, matched, considered, null, null,
                    "没有规则命中；系统采用保守结果，不生成可删除结论", null, null));
        }
        int governingPriority = intValue(matchedRules.get(0).get("priority"));
        List<Map<String, Object>> governingGroup = matchedRules.stream()
                .filter(rule -> intValue(rule.get("priority")) == governingPriority)
                .toList();
        Map<String, Object> governing = governingGroup.stream()
                .max(Comparator.comparingInt((Map<String, Object> rule) -> intValue(rule.get("retentionDays")))
                        .thenComparing(rule -> String.valueOf(rule.get("ruleId"))))
                .orElseThrow();
        int strictestDays = intValue(governing.get("retentionDays"));
        boolean eligible = ageDays >= strictestDays;
        String disposition = eligible ? "DELETABLE" : "RETAIN_POLICY";
        String priorityText = "priority 数值最小的优先级 " + governingPriority + " 获胜；同优先级取最大保留天数";
        String message = eligible
                ? priorityText + "，最严格保留期为 " + strictestDays + " 天；资源年龄 " + ageDays + " 天，已到期可删"
                : priorityText + "，最严格保留期为 " + strictestDays + " 天；资源年龄 " + ageDays + " 天，尚未到期，必须保留";
        return new Evaluation(disposition, explanation(resource, at, ageDays, accessAgeDays, matched, considered, governing, strictestDays, message, null, null));
    }

    private boolean matchesRule(Map<String, Object> resource, Map<String, Object> rule,
                                long ageDays, long accessAgeDays, Map<String, Object> result) {
        String category = String.valueOf(rule.getOrDefault("category", ""));
        boolean categoryMatches = category.isBlank() || category.equals("*") || category.equals(resource.get("category"));
        result.put("categoryMatches", categoryMatches);

        int minAge = rule.containsKey("minAgeDays") ? intValue(rule.get("minAgeDays")) : 0;
        int maxAge = rule.containsKey("maxAgeDays") ? intValue(rule.get("maxAgeDays")) : Integer.MAX_VALUE;
        boolean ageMatches = ageDays >= minAge && ageDays <= maxAge;
        result.put("ageDays", ageDays);
        result.put("minAgeDays", minAge);
        result.put("maxAgeDays", maxAge == Integer.MAX_VALUE ? null : maxAge);
        result.put("ageMatches", ageMatches);

        int minAccessAge = rule.containsKey("minLastAccessAgeDays") ? intValue(rule.get("minLastAccessAgeDays")) : 0;
        int maxAccessAge = rule.containsKey("maxLastAccessAgeDays") ? intValue(rule.get("maxLastAccessAgeDays")) : Integer.MAX_VALUE;
        boolean accessMatches = accessAgeDays >= minAccessAge && accessAgeDays <= maxAccessAge;
        result.put("lastAccessAgeDays", accessAgeDays);
        result.put("lastAccessMatches", accessMatches);

        List<String> required = new ArrayList<>();
        List<String> any = new ArrayList<>();
        for (Object tag : Json.list(rule.get("requiredTags"))) {
            required.add(String.valueOf(tag));
        }
        for (Object tag : Json.list(rule.get("anyTags"))) {
            any.add(String.valueOf(tag));
        }
        List<String> resourceTags = new ArrayList<>();
        for (Object tag : Json.list(resource.get("tags"))) {
            resourceTags.add(String.valueOf(tag));
        }
        Set<String> tagSet = Set.copyOf(resourceTags);
        boolean requiredMatches = tagSet.containsAll(required);
        boolean anyMatches = any.isEmpty() || any.stream().anyMatch(tagSet::contains);
        result.put("requiredTags", required);
        result.put("anyTags", any);
        result.put("resourceTags", resourceTags);
        result.put("requiredTagsMatch", requiredMatches);
        result.put("anyTagsMatch", anyMatches);
        return categoryMatches && ageMatches && accessMatches && requiredMatches && anyMatches;
    }

    private Map<String, Object> explanation(Map<String, Object> resource, String at, long ageDays, long accessAgeDays,
                                            List<Object> matched, List<Object> considered,
                                            Map<String, Object> governing, Integer strictestDays, String message,
                                            Map<String, Object> activeHold, Object fingerprint) {
        Json.JObject map = new Json.JObject();
        map.put("resourceId", resource.get("id"));
        map.put("evaluatedAt", at);
        map.put("resourceAgeDays", ageDays);
        map.put("lastAccessAgeDays", accessAgeDays);
        map.put("matchedRules", matched);
        map.put("consideredRules", considered);
        map.put("governingRuleId", governing == null ? null : governing.get("ruleId"));
        map.put("strictestRetentionDays", strictestDays);
        map.put("priorityRule", "priority 数值最小的规则组获胜；同优先级取最大保留天数；仍相同按 ruleId 稳定排序");
        map.put("message", message);
        map.put("activeHold", activeHold);
        map.put("resourceFingerprint", fingerprint == null ? resource.get("fingerprint") : fingerprint);
        return map;
    }

    private Optional<Map<String, Object>> activeHold(String resourceId, String at) {
        for (Object hold : activeHolds(resourceId, at)) {
            return Optional.of(castObject(hold));
        }
        return Optional.empty();
    }

    private Json.JArray activeHolds(String resourceId, String at) {
        Json.JArray result = new Json.JArray();
        List<Map<String, Object>> openHolds = new ArrayList<>();
        try {
            PreparedStatement statement = db.connection().prepareStatement("""
                SELECT * FROM legal_holds
                WHERE resource_id=? AND effective_from <= ?
                ORDER BY effective_from, created_at, id
                """);
            statement.setString(1, resourceId);
            statement.setString(2, at);
            ResultSet resultSet = statement.executeQuery();
            while (resultSet.next()) {
                Map<String, Object> event = holdFromRow(resultSet);
                String type = String.valueOf(event.get("eventType"));
                String to = event.get("effectiveTo") == null ? null : String.valueOf(event.get("effectiveTo"));
                if ("HOLD".equals(type) && (to == null || Instant.parse(to).isAfter(Instant.parse(at)))) {
                    openHolds.add(event);
                } else if ("RELEASE".equals(type)) {
                    String targetHoldId = event.get("targetHoldId") == null ? null : String.valueOf(event.get("targetHoldId"));
                    if (targetHoldId != null) {
                        openHolds.removeIf(hold -> targetHoldId.equals(String.valueOf(hold.get("id"))));
                    } else if (!openHolds.isEmpty()) {
                        openHolds.remove(openHolds.size() - 1);
                    }
                }
            }
            openHolds.sort(Comparator.comparing((Map<String, Object> hold) -> String.valueOf(hold.get("effectiveFrom"))).reversed());
            result.addAll(openHolds);
            return result;
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot evaluate holds", exception);
        }
    }

    private Map<String, Object> baseResourceSnapshot(String id, Map<String, Object> body, String createdAt, String lastAccessedAt) {
        Json.JObject snapshot = new Json.JObject();
        snapshot.put("id", id);
        snapshot.put("name", requireText(body, "name"));
        snapshot.put("category", requireText(body, "category"));
        snapshot.put("createdAt", createdAt);
        snapshot.put("lastAccessedAt", lastAccessedAt);
        Json.JArray tags = new Json.JArray();
        for (Object tag : Json.list(body.get("tags"))) {
            tags.add(String.valueOf(tag));
        }
        tags.sort((left, right) -> String.valueOf(left).compareTo(String.valueOf(right)));
        snapshot.put("tags", tags);
        snapshot.put("contentSummary", requireText(body, "contentSummary"));
        return snapshot;
    }

    private Map<String, Object> merged(Map<String, Object> update, Map<String, Object> current) {
        Json.JObject merged = new Json.JObject();
        merged.putAll(current);
        merged.putAll(update);
        merged.put("tags", update.containsKey("tags") ? update.get("tags") : current.get("tags"));
        return merged;
    }

    private Set<String> selectedItems(Object raw) {
        Set<String> result = new java.util.HashSet<>();
        for (Object item : Json.list(raw)) {
            result.add(String.valueOf(item));
        }
        return result;
    }

    private void validateRule(Map<String, Object> rule) {
        String ruleId = require(rule, "ruleId");
        require(rule, "category");
        Integer priority = integer(rule, "priority");
        Integer retentionDays = integer(rule, "retentionDays");
        if (priority == null || priority < 1) {
            throw new ApiException(400, "rule " + ruleId + " requires positive integer priority");
        }
        if (retentionDays == null || retentionDays < 0) {
            throw new ApiException(400, "rule " + ruleId + " requires non-negative retentionDays");
        }
        validateOptionalInt(rule, "minAgeDays");
        validateOptionalInt(rule, "maxAgeDays");
        validateOptionalInt(rule, "minLastAccessAgeDays");
        validateOptionalInt(rule, "maxLastAccessAgeDays");
    }

    private void validateOptionalInt(Map<String, Object> map, String key) {
        Integer value = integer(map, key);
        if (value != null && value < 0) {
            throw new ApiException(400, key + " must be a non-negative integer");
        }
    }

    private List<Map<String, Object>> rulesFromBody(Object rawRules) {
        Json.JArray normalized = new Json.JArray();
        for (Object raw : Json.list(rawRules)) {
            if (!(raw instanceof Map<?, ?>)) {
                throw new ApiException(400, "rules must be objects");
            }
            normalized.add(castObject(raw));
        }
        if (normalized.isEmpty()) {
            throw new ApiException(400, "rules must contain at least one rule");
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object raw : normalized) {
            result.add(castObject(raw));
        }
        return result;
    }

    private Integer integer(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.valueOf(value.toString());
        } catch (NumberFormatException exception) {
            throw new ApiException(400, key + " must be an integer");
        }
    }

    private Map<String, Object> requireResource(String resourceId, boolean includeDeletedOkay) {
        try {
            PreparedStatement statement = db.connection().prepareStatement("SELECT * FROM resources WHERE id=?");
            statement.setString(1, resourceId);
            ResultSet resultSet = statement.executeQuery();
            if (!resultSet.next()) {
                throw new ApiException(404, "resource not found: " + resourceId);
            }
            Map<String, Object> map = resourceFromRow(resultSet);
            if (!includeDeletedOkay && Boolean.TRUE.equals(map.get("deleted"))) {
                map.put("readableForAudit", true);
            }
            return map;
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot read resource", exception);
        }
    }

    private Map<String, Object> resourceFromRow(ResultSet row) throws SQLException {
        Json.JObject map = new Json.JObject();
        map.put("id", row.getString("id"));
        map.put("name", row.getString("name"));
        map.put("category", row.getString("category"));
        map.put("createdAt", row.getString("created_at"));
        map.put("lastAccessedAt", row.getString("last_accessed_at"));
        map.put("tags", Json.parse(row.getString("tags_json")));
        map.put("contentSummary", row.getString("content_summary"));
        map.put("currentVersion", row.getInt("current_version"));
        map.put("deleted", row.getInt("is_deleted") == 1);
        map.put("recordedAt", row.getString("created_at_record"));
        map.put("fingerprint", currentFingerprint(map));
        return map;
    }

    private List<Map<String, Object>> allResourceMaps(boolean includeDeleted) {
        List<Map<String, Object>> resources = new ArrayList<>();
        try {
            PreparedStatement statement = db.connection().prepareStatement("SELECT * FROM resources WHERE is_deleted=0 OR ? ORDER BY id");
            statement.setBoolean(1, includeDeleted);
            ResultSet resultSet = statement.executeQuery();
            while (resultSet.next()) {
                resources.add(resourceFromRow(resultSet));
            }
            return resources;
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot read resources", exception);
        }
    }

    private Map<String, Object> resourceVersion(String resourceId, int version) {
        try (PreparedStatement statement = db.connection().prepareStatement(
                "SELECT id, snapshot_json FROM resource_versions WHERE resource_id=? AND version=?")) {
            statement.setString(1, resourceId);
            statement.setInt(2, version);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new ApiException(404, "resource version not found");
                }
                Map<String, Object> snapshot = castObject(Json.parse(resultSet.getString("snapshot_json")));
                snapshot.put("versionId", resultSet.getString("id"));
                return snapshot;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot read resource version", exception);
        }
    }

    private String currentFingerprint(String resourceId) {
        return currentFingerprint(requireResource(resourceId, true));
    }

    private String currentFingerprint(Map<String, Object> resource) {
        Json.JArray tags = new Json.JArray();
        tags.addAll(Json.list(resource.get("tags")));
        tags.sort((left, right) -> String.valueOf(left).compareTo(String.valueOf(right)));
        Json.JObject canonical = new Json.JObject();
        canonical.put("id", resource.get("id"));
        canonical.put("name", resource.get("name"));
        canonical.put("category", resource.get("category"));
        canonical.put("createdAt", resource.get("createdAt"));
        canonical.put("lastAccessedAt", resource.get("lastAccessedAt"));
        canonical.put("tags", tags);
        canonical.put("contentSummary", resource.get("contentSummary"));
        canonical.put("version", resource.get("currentVersion"));
        return Hashes.sha256(Json.write(canonical));
    }

    private Optional<Map<String, Object>> latestPolicy() {
        try {
            ResultSet resultSet = db.connection().createStatement().executeQuery(
                    "SELECT snapshot_json FROM policy_versions ORDER BY version DESC LIMIT 1");
            if (resultSet.next()) {
                return Optional.of(castObject(Json.parse(resultSet.getString(1))));
            }
            return Optional.empty();
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot read latest policy", exception);
        }
    }

    private Map<String, Object> requirePolicy(String policyId) {
        try {
            PreparedStatement statement = db.connection().prepareStatement("SELECT snapshot_json FROM policy_versions WHERE id=?");
            statement.setString(1, policyId);
            ResultSet resultSet = statement.executeQuery();
            if (!resultSet.next()) {
                throw new ApiException(404, "policy version not found: " + policyId);
            }
            return castObject(Json.parse(resultSet.getString(1)));
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot read policy", exception);
        }
    }

    private int nextPolicyVersion() {
        try {
            ResultSet resultSet = db.connection().createStatement().executeQuery("SELECT COALESCE(MAX(version),0) FROM policy_versions");
            resultSet.next();
            return resultSet.getInt(1);
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot read policy version", exception);
        }
    }

    private Map<String, Object> holdFromRow(ResultSet row) throws SQLException {
        Json.JObject map = new Json.JObject();
        map.put("id", row.getString("id"));
        map.put("resourceId", row.getString("resource_id"));
        map.put("eventType", row.getString("event_type"));
        map.put("effectiveFrom", row.getString("effective_from"));
        map.put("effectiveTo", row.getString("effective_to"));
        map.put("targetHoldId", row.getString("target_hold_id"));
        map.put("reason", row.getString("reason"));
        map.put("createdAt", row.getString("created_at"));
        map.put("immutable", true);
        return map;
    }

    private Map<String, Object> holdById(String holdId) {
        try {
            PreparedStatement statement = db.connection().prepareStatement("SELECT * FROM legal_holds WHERE id=?");
            statement.setString(1, holdId);
            ResultSet resultSet = statement.executeQuery();
            if (!resultSet.next()) {
                throw new ApiException(404, "hold not found");
            }
            return holdFromRow(resultSet);
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot read hold", exception);
        }
    }

    private void insertPlanItem(String itemId, String planId, int seq, Map<String, Object> resource,
                                Map<String, Object> version, Evaluation evaluation, String status) throws SQLException {
        PreparedStatement statement = db.connection().prepareStatement("""
            INSERT INTO plan_items(id,plan_id,seq,resource_id,resource_version_id,resource_fingerprint,initial_disposition,status,explanation_json,blocked_reason)
            VALUES (?,?,?,?,?,?,?,?,?,NULL)
            """);
        statement.setString(1, itemId);
        statement.setString(2, planId);
        statement.setInt(3, seq);
        statement.setString(4, resource.get("id").toString());
        statement.setString(5, version.get("versionId").toString());
        statement.setString(6, version.get("fingerprint").toString());
        statement.setString(7, evaluation.disposition());
        statement.setString(8, status);
        statement.setString(9, Json.write(evaluation.explanation()));
        statement.executeUpdate();
    }

    private Map<String, Object> planItemMap(String id, String planId, int seq, String resourceId, String resourceVersionId,
                                            String fingerprint, String disposition, String status,
                                            Map<String, Object> explanation, String blockedReason) {
        Json.JObject map = new Json.JObject();
        map.put("id", id);
        map.put("planId", planId);
        map.put("seq", seq);
        map.put("resourceId", resourceId);
        map.put("resourceVersionId", resourceVersionId);
        map.put("resourceFingerprint", fingerprint);
        map.put("initialDisposition", disposition);
        map.put("status", status);
        map.put("frozenExplanation", explanation);
        map.put("blockedReason", blockedReason);
        return map;
    }

    private Map<String, Object> planItemFromRow(ResultSet row) throws SQLException {
        return planItemMap(row.getString("id"), row.getString("plan_id"), row.getInt("seq"),
                row.getString("resource_id"), row.getString("resource_version_id"),
                row.getString("resource_fingerprint"), row.getString("initial_disposition"),
                row.getString("status"), castObject(Json.parse(row.getString("explanation_json"))),
                row.getString("blocked_reason"));
    }

    private Map<String, Object> planRow(String planId) {
        try {
            PreparedStatement statement = db.connection().prepareStatement("SELECT * FROM plans WHERE id=?");
            statement.setString(1, planId);
            ResultSet resultSet = statement.executeQuery();
            if (!resultSet.next()) {
                throw new ApiException(404, "plan not found: " + planId);
            }
            Json.JObject map = new Json.JObject();
            map.put("id", resultSet.getString("id"));
            map.put("version", resultSet.getInt("version"));
            map.put("frozenAt", resultSet.getString("frozen_at"));
            map.put("policyVersionId", resultSet.getString("policy_version_id"));
            map.put("policyVersionNumber", resultSet.getInt("policy_version_number"));
            map.put("policyFingerprint", resultSet.getString("policy_fingerprint"));
            map.put("status", resultSet.getString("status"));
            map.put("note", resultSet.getString("note"));
            map.put("createdAt", resultSet.getString("created_at"));
            return map;
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot read plan", exception);
        }
    }

    private Map<String, Object> planSummary(String planId) {
        Map<String, Object> plan = planRow(planId);
        Json.JArray items = new Json.JArray();
        for (Map<String, Object> item : planItemRows(planId)) {
            items.add(item);
        }
        Json.JObject result = new Json.JObject();
        result.putAll(plan);
        result.put("items", items);
        return result;
    }

    private List<Map<String, Object>> planItemRows(String planId) {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            PreparedStatement statement = db.connection().prepareStatement(
                    "SELECT * FROM plan_items WHERE plan_id=? ORDER BY seq");
            statement.setString(1, planId);
            ResultSet resultSet = statement.executeQuery();
            while (resultSet.next()) {
                result.add(planItemFromRow(resultSet));
            }
            return result;
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot read plan items", exception);
        }
    }

    private Map<String, Object> planItemRow(String itemId) {
        try {
            PreparedStatement statement = db.connection().prepareStatement("SELECT * FROM plan_items WHERE id=?");
            statement.setString(1, itemId);
            ResultSet resultSet = statement.executeQuery();
            if (!resultSet.next()) {
                throw new ApiException(404, "plan item not found: " + itemId);
            }
            return planItemFromRow(resultSet);
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot read plan item", exception);
        }
    }

    private Optional<Map<String, Object>> executionByPlan(String planId) {
        try {
            PreparedStatement statement = db.connection().prepareStatement("SELECT id FROM executions WHERE plan_id=?");
            statement.setString(1, planId);
            ResultSet resultSet = statement.executeQuery();
            if (resultSet.next()) {
                return Optional.of(executionSummary(resultSet.getString(1)));
            }
            return Optional.empty();
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot read execution", exception);
        }
    }

    private String executionIdByPlan(String planId) {
        return executionByPlan(planId).orElseThrow(() -> new ApiException(409, "execution generation missing")).get("id").toString();
    }

    private Map<String, Object> executionSummary(String executionId) {
        try {
            PreparedStatement statement = db.connection().prepareStatement("SELECT * FROM executions WHERE id=?");
            statement.setString(1, executionId);
            ResultSet resultSet = statement.executeQuery();
            if (!resultSet.next()) {
                throw new ApiException(404, "execution not found: " + executionId);
            }
            Json.JObject map = new Json.JObject();
            map.put("id", resultSet.getString("id"));
            map.put("planId", resultSet.getString("plan_id"));
            map.put("generation", resultSet.getInt("generation"));
            map.put("status", resultSet.getString("status"));
            map.put("operator", resultSet.getString("operator"));
            map.put("idempotencyKey", resultSet.getString("idempotency_key"));
            map.put("expectedPlanVersion", resultSet.getInt("expected_plan_version"));
            map.put("createdAt", resultSet.getString("created_at"));
            map.put("updatedAt", resultSet.getString("updated_at"));
            map.put("completedAt", resultSet.getString("completed_at"));
            map.put("receipts", receipts(executionId));
            return map;
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot read execution", exception);
        }
    }

    private Json.JArray receipts(String executionId) {
        Json.JArray array = new Json.JArray();
        try {
            PreparedStatement statement = db.connection().prepareStatement(
                    "SELECT * FROM execution_receipts WHERE execution_id=? ORDER BY seq");
            statement.setString(1, executionId);
            ResultSet resultSet = statement.executeQuery();
            while (resultSet.next()) {
                array.add(receiptFromRow(resultSet));
            }
            return array;
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot read receipts", exception);
        }
    }

    private Map<String, Object> receiptFromRow(ResultSet row) throws SQLException {
        Json.JObject map = new Json.JObject();
        map.put("id", row.getString("id"));
        map.put("executionId", row.getString("execution_id"));
        map.put("planItemId", row.getString("plan_item_id"));
        map.put("seq", row.getInt("seq"));
        map.put("itemToken", row.getString("item_token"));
        map.put("status", row.getString("status"));
        map.put("reason", row.getString("reason"));
        map.put("requestToken", row.getString("request_token"));
        map.put("startedAt", row.getString("started_at"));
        map.put("completedAt", row.getString("completed_at"));
        return map;
    }

    private String receiptId(String executionId, String itemId) {
        try {
            PreparedStatement statement = db.connection().prepareStatement(
                    "SELECT id FROM execution_receipts WHERE execution_id=? AND plan_item_id=? AND status='PROCESSING'");
            statement.setString(1, executionId);
            statement.setString(2, itemId);
            ResultSet resultSet = statement.executeQuery();
            if (!resultSet.next()) {
                throw new ApiException(409, "processing receipt missing");
            }
            return resultSet.getString(1);
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot read receipt", exception);
        }
    }

    private boolean ledgerExistsByItem(String itemId) {
        try {
            PreparedStatement statement = db.connection().prepareStatement(
                    "SELECT 1 FROM delete_ledger WHERE plan_item_id=?");
            statement.setString(1, itemId);
            return statement.executeQuery().next();
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot inspect delete ledger", exception);
        }
    }

    private int countUnknownItems(String planId) {
        try {
            PreparedStatement statement = db.connection().prepareStatement(
                    "SELECT COUNT(*) FROM plan_items WHERE plan_id=? AND status='UNKNOWN'");
            statement.setString(1, planId);
            ResultSet resultSet = statement.executeQuery();
            resultSet.next();
            return resultSet.getInt(1);
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot count unknown items", exception);
        }
    }

    private void updateExecutionStatus(String executionId, String status, String completedAt) {
        try {
            PreparedStatement statement = db.connection().prepareStatement(
                    "UPDATE executions SET status=?, updated_at=?, completed_at=? WHERE id=?");
            statement.setString(1, status);
            statement.setString(2, clock.now());
            if (completedAt == null) {
                statement.setNull(3, java.sql.Types.VARCHAR);
            } else {
                statement.setString(3, completedAt);
            }
            statement.setString(4, executionId);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot update execution", exception);
        }
    }

    private void markInterrupted(String executionId) {
        updateExecutionStatus(executionId, "INTERRUPTED", null);
    }

    private void updatePlanStatus(String planId, String status) {
        try {
            PreparedStatement statement = db.connection().prepareStatement("UPDATE plans SET status=? WHERE id=?");
            statement.setString(1, status);
            statement.setString(2, planId);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot update plan status", exception);
        }
    }

    private void insertEvent(String executionId, String itemId, Integer seq, String type, Object detail) {
        try {
            PreparedStatement statement = db.connection().prepareStatement("""
                INSERT INTO execution_events(id,execution_id,plan_item_id,seq,event_type,detail_json,created_at)
                VALUES (?,?,?,?,?,?,?)
                """);
            statement.setString(1, "EV-" + randomId());
            statement.setString(2, executionId);
            if (itemId == null) {
                statement.setNull(3, java.sql.Types.VARCHAR);
            } else {
                statement.setString(3, itemId);
            }
            if (seq == null) {
                statement.setNull(4, java.sql.Types.INTEGER);
            } else {
                statement.setInt(4, seq);
            }
            statement.setString(5, type);
            statement.setString(6, Json.write(detail));
            statement.setString(7, clock.now());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot insert event", exception);
        }
    }

    private void insertEventForItem(String itemId, String type, Object detail) {
        Map<String, Object> item = planItemRow(itemId);
        insertEvent(executionIdByPlan(item.get("planId").toString()), itemId, intValue(item.get("seq")), type, detail);
    }

    private String itemToken(String itemId, String requestToken) {
        return Hashes.sha256(itemId + "|" + requestToken);
    }

    private Map<String, Object> summary(String table, String key, String value) {
        if (table.equals("plans")) {
            return planSummary(value);
        }
        throw new IllegalArgumentException(table);
    }

    private Map<String, Object> response(String key, Object value) {
        Json.JObject map = new Json.JObject();
        map.put(key, value);
        return map;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castResponse(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castObjectMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castObject(Object object) {
        if (object instanceof Map<?, ?>) {
            return (Map<String, Object>) object;
        }
        throw new ApiException(500, "expected JSON object");
    }

    private String require(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null || value.toString().isBlank()) {
            throw new ApiException(400, key + " is required");
        }
        return value.toString();
    }

    private String requireText(Map<String, Object> map, String key) {
        return require(map, key);
    }

    private String str(Map<String, Object> map, String key, String fallback) {
        Object value = map.get(key);
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }

    private int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private boolean unique(SQLException exception) {
        return exception.getMessage() != null && exception.getMessage().toLowerCase(Locale.ROOT).contains("unique");
    }

    private String randomId() {
        return UUID.randomUUID().toString().substring(0, 12);
    }

    public static final class SimulatedExitException extends ApiException {
        private final String executionId;

        private SimulatedExitException(int status, String message, String executionId) {
            super(status, message);
            this.executionId = executionId;
        }

        public String executionId() {
            return executionId;
        }
    }

    private record Evaluation(String disposition, Map<String, Object> explanation) {
    }
}
