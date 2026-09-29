package org.example.retention;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RetentionWorkflowTest {
    private static Path directory;
    private static Database database;
    private static Clock clock;
    private static RetentionService service;
    private static HttpServer server;
    private static int port;

    static Response request(String method, String path, String body, Map<String, String> headers) throws IOException, InterruptedException {
        if (port == 0) start();
        HttpURLConnection connection = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + path).toURL().openConnection(Proxy.NO_PROXY);
        connection.setRequestMethod(method);
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        headers.forEach(connection::setRequestProperty);
        if (body != null) {
            connection.setDoOutput(true);
            connection.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        }
        int status = connection.getResponseCode();
        String text = new String((status >= 400 ? connection.getErrorStream() : connection.getInputStream()).readAllBytes(), StandardCharsets.UTF_8);
        String contentType = connection.getContentType();
        boolean json = contentType != null && contentType.contains("application/json");
        return new Response(status, text, json && !text.isBlank() ? Json.parseObject(text) : Map.of());
    }

    static Response request(String method, String path, String body) throws IOException, InterruptedException {
        return request(method, path, body, body == null ? Map.of() : Map.of("Content-Type", "application/json"));
    }

    static void start() throws IOException {
        directory = Files.createTempDirectory("retention-test-");
        database = Database.open(directory.resolve("retention.db"));
        clock = new Clock(database);
        service = new RetentionService(database, clock);
        SeedData.seedIfEmpty(service);
        server = new HttpServer(service);
        server.start(0);
        port = server.port();
    }

    static void restart() throws IOException {
        server.stop();
        try {
            database.close();
        } catch (Exception ignored) {
        }
        database = Database.open(directory.resolve("retention.db"));
        clock = new Clock(database);
        service = new RetentionService(database, clock);
        server = new HttpServer(service);
        server.start(0);
        port = server.port();
    }

    static Map<String, Object> data(Response response) {
        return response.body;
    }

    static String findItemId(String resourceId) throws IOException, InterruptedException {
        Response response = request("GET", "/api/plans/" + SeedData.PLAN_ID, null);
        assertEquals(200, response.status);
        for (Object raw : Json.list(data(response).get("plan") == null ? null : cast(data(response).get("plan")).get("items"))) {
            Map<String, Object> item = cast(raw);
            if (resourceId.equals(item.get("resourceId"))) {
                return item.get("id").toString();
            }
        }
        throw new AssertionError(resourceId + " missing");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> cast(Object value) {
        return (Map<String, Object>) value;
    }

    static Map<String, Object> item(String resourceId) throws IOException, InterruptedException {
        Response response = request("GET", "/api/plans/" + SeedData.PLAN_ID, null);
        for (Object raw : cast(data(response).get("plan")).get("items") instanceof List<?> list ? list : List.of()) {
            Map<String, Object> item = cast(raw);
            if (resourceId.equals(item.get("resourceId"))) {
                return item;
            }
        }
        throw new AssertionError(resourceId);
    }

    @Test
    @Order(1)
    void pageClockAndSeededScenariosAreExplicit() throws IOException, InterruptedException {
        Response page = request("GET", "/", null);
        assertEquals(200, page.status, page.text);
        assertTrue(page.text.contains("保留策略演算台"));

        Response clockResponse = request("GET", "/api/clock", null);
        assertEquals(200, clockResponse.status);
        assertEquals("2026-09-29T00:00:00Z", cast(data(clockResponse).get("clock")).get("now"));

        Response planResponse = request("GET", "/api/plans/" + SeedData.PLAN_ID, null);
        assertEquals(200, planResponse.status);
        Map<String, Object> plan = cast(data(planResponse).get("plan"));
        assertEquals(2, ((Number) plan.get("version")).intValue());
        assertEquals("APPROVED", plan.get("status"));

        Map<String, Object> conflict = item(SeedData.CONFLICT_RESOURCE);
        assertEquals("DELETED", conflict.get("status"));
        assertEquals("RULE-LOG-SPECIFIC", cast(conflict.get("frozenExplanation")).get("governingRuleId"));
        assertEquals(90, ((Number) cast(conflict.get("frozenExplanation")).get("strictestRetentionDays")).intValue());

        Map<String, Object> interrupted = item(SeedData.INTERRUPT_RESOURCE);
        assertEquals("UNKNOWN", interrupted.get("status"));
        assertEquals("DELETABLE", interrupted.get("initialDisposition"));

        Map<String, Object> blocked = item(SeedData.NEW_HOLD_RESOURCE);
        assertEquals("BLOCKED", blocked.get("status"));
        assertNull(cast(blocked.get("frozenExplanation")).get("activeHold"));
        assertTrue(blocked.get("blockedReason").toString().contains("法律保留"));

        Response trace = request("GET", "/api/resources/" + SeedData.INTERRUPT_RESOURCE + "/trace", null);
        assertEquals(200, trace.status);
        Map<String, Object> traceBody = cast(data(trace).get("trace"));
        assertFalse(Json.list(traceBody.get("receipts")).isEmpty());
        assertFalse(Json.list(traceBody.get("planItems")).isEmpty());

        Response holdTrace = request("GET", "/api/resources/" + SeedData.NEW_HOLD_RESOURCE + "/trace", null);
        assertEquals(200, holdTrace.status);
        Map<String, Object> holdTraceBody = cast(data(holdTrace).get("trace"));
        assertFalse(Json.list(holdTraceBody.get("legalHolds")).isEmpty());
    }

    @Test
    @Order(2)
    void unknownResultCannotBeRedoneUntilVerifiedAndSurvivesRestart() throws Exception {
        String itemId = findItemId(SeedData.INTERRUPT_RESOURCE);

        Response unsafeResume = request("POST", "/api/plans/" + SeedData.PLAN_ID + "/executions",
                Json.write(Map.of("idempotencyKey", "must-be-rejected", "runAt", "2026-09-29T00:00:00Z")));
        assertEquals(409, unsafeResume.status);
        assertTrue(data(unsafeResume).get("error").toString().contains("verified"));

        Response verify = request("POST", "/api/plans-items".replace("/api/plans-items", "/api/plan-items") + "/" + itemId + "/verify",
                Json.write(Map.of("requestToken", "manual-verification")));
        assertEquals(200, verify.status);
        Map<String, Object> verification = cast(data(verify).get("verification"));
        assertEquals(false, verification.get("ledgerRecordFound"));
        assertEquals("SAFE_TO_DECIDE_LATER", verification.get("decision"));
        assertEquals("PENDING", verification.get("itemStatus"));

        restart();
        Response afterRestart = request("GET", "/api/plans/" + SeedData.PLAN_ID, null);
        assertEquals(200, afterRestart.status);
        assertEquals("PENDING", item(SeedData.INTERRUPT_RESOURCE).get("status"));

        Response resume = request("POST", "/api/plans/" + SeedData.PLAN_ID + "/executions",
                Json.write(Map.of("idempotencyKey", "resume-after-verification", "runAt", "2026-09-30T00:00:00Z")));
        assertEquals(200, resume.status);
        assertEquals("DELETED", item(SeedData.INTERRUPT_RESOURCE).get("status"));
    }

    @Test
    @Order(3)
    void concurrentApprovalCreatesExactlyOneGenerationAndRejectsStaleVersion() throws Exception {
        String id = "R-CONCURRENT";
        String resource = Json.write(Map.of(
                "id", id,
                "name", "并发批准",
                "category", "report",
                "createdAt", "2025-01-01T00:00:00Z",
                "lastAccessedAt", "2026-01-01T00:00:00Z",
                "tags", List.of("report"),
                "contentSummary", "sha256:concurrent"));
        assertEquals(201, request("POST", "/api/resources", resource).status);
        String policy = Json.write(Map.of("note", "并发", "rules", List.of(Map.of(
                "ruleId", "REPORT", "category", "report", "priority", 1, "retentionDays", 180))));
        Response policyResponse = request("POST", "/api/policies", policy);
        assertEquals(201, policyResponse.status);
        String policyId = cast(data(policyResponse).get("policyVersion")).get("id").toString();
        String plan = Json.write(Map.of("id", "PLAN-CONCURRENT", "policyVersionId", policyId,
                "frozenAt", "2026-09-29T00:00:00Z"));
        assertEquals(201, request("POST", "/api/plans", plan).status);

        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        Runnable alice = () -> approve(start, success, conflict, "alice-key");
        Runnable bob = () -> approve(start, success, conflict, "bob-key");
        Thread first = new Thread(alice);
        Thread second = new Thread(bob);
        first.start();
        second.start();
        start.countDown();
        first.join(5000);
        second.join(5000);
        assertEquals(1, success.get());
        assertEquals(1, conflict.get());

        Response plans = request("GET", "/api/plans/PLAN-CONCURRENT", null);
        Map<String, Object> approved = cast(data(plans).get("plan"));
        assertEquals(2, ((Number) approved.get("version")).intValue());

        Response duplicateKey = request("POST", "/api/plans/PLAN-CONCURRENT/approve",
                Json.write(Map.of("expectedPlanVersion", 2, "operator", "alice-retry", "idempotencyKey", "alice-key")));
        assertEquals(200, duplicateKey.status);
    }

    void approve(CountDownLatch start, AtomicInteger success, AtomicInteger conflict, String key) {
        try {
            start.await();
            Response response = request("POST", "/api/plans/PLAN-CONCURRENT/approve",
                    Json.write(Map.of("expectedPlanVersion", 1, "operator", key, "idempotencyKey", key)));
            if (response.status == 200) {
                success.incrementAndGet();
            } else if (response.status == 409) {
                conflict.incrementAndGet();
            } else {
                throw new AssertionError("unexpected status " + response.status + " " + response.text);
            }
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    @Test
    @Order(4)
    void frozenPlanKeepsExplanationWhenLaterHoldAppears() throws Exception {
        Map<String, Object> before = item(SeedData.NEW_HOLD_RESOURCE);
        String blockedReason = before.get("blockedReason").toString();
        Object activeHoldAtFreeze = cast(before.get("frozenExplanation")).get("activeHold");
        assertNull(activeHoldAtFreeze);
        assertTrue(blockedReason.contains("法律保留"), blockedReason);

        Response holds = request("GET", "/api/holds?resourceId=" + SeedData.NEW_HOLD_RESOURCE, null);
        assertEquals(200, holds.status);
        assertEquals(1, Json.list(data(holds).get("holds")).size());
    }

    @Test
    @Order(5)
    void controlledClockAndAppendOnlyHoldContractAreEnforced() throws Exception {
        String futureClock = Json.write(Map.of("now", "2026-10-01T00:00:00Z"));
        assertEquals(200, request("POST", "/api/clock", futureClock).status);
        assertEquals(400, request("POST", "/api/clock", Json.write(Map.of("now", "not-a-date"))).status);

        try (Connection connection = database.connection()) {
            try (PreparedStatement statement = connection.prepareStatement("UPDATE legal_holds SET reason='changed' WHERE 1=1")) {
                assertThrows(java.sql.SQLException.class, statement::executeUpdate);
            }
        }
    }

    record Response(int status, String text, Map<String, Object> body) {
        Response(int status, String text, Map<String, Object> body) {
            this.status = status;
            this.text = text;
            this.body = body;
        }

        Response(int status, Map<String, Object> body) {
            this(status, Json.write(body), body);
        }
    }
}
