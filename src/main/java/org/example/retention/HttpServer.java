package org.example.retention;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public final class HttpServer {
    private final RetentionService service;
    private com.sun.net.httpserver.HttpServer server;

    public HttpServer(RetentionService service) {
        this.service = service;
    }

    public void start(int port) {
        try {
            server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(port), 0);
            server.createContext("/", new Router());
            server.start();
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot start HTTP server", exception);
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    public int port() {
        return server == null ? -1 : server.getAddress().getPort();
    }

    private final class Router implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) {
            try {
                if ("GET".equals(exchange.getRequestMethod()) && (exchange.getRequestURI().getPath().equals("/") || exchange.getRequestURI().getPath().equals("/index.html"))) {
                    servePage(exchange);
                    return;
                }
                if (exchange.getRequestURI().getPath().startsWith("/api/")) {
                    synchronized (service) {
                        routeApi(exchange);
                    }
                    return;
                }
                sendError(exchange, 404, "not found");
            } catch (ApiException exception) {
                try {
                    sendError(exchange, exception.status(), exception.getMessage(),
                            exception instanceof RetentionService.SimulatedExitException simulated
                                    ? Map.of("executionId", simulated.executionId()) : Map.of());
                } catch (IOException ignored) {
                }
            } catch (Exception exception) {
                try {
                    sendError(exchange, 500, exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage());
                } catch (IOException ignored) {
                }
            } finally {
                exchange.close();
            }
        }
    }

    private void routeApi(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        Map<String, String> query = query(exchange);
        Optional<String> at = Optional.ofNullable(exchange.getRequestHeaders().getFirst("X-At-Time"))
                .filter(value -> !value.isBlank());
        Map<String, Object> body = "GET".equals(method) ? new Json.JObject() : readBody(exchange);

        if ("GET".equals(method) && path.equals("/api/clock")) {
            send(exchange, service.clockStatus());
        } else if ("POST".equals(method) && path.equals("/api/clock")) {
            send(exchange, service.setClock(Json.string(body, "now")));
        } else if ("POST".equals(method) && path.equals("/api/clock/advance")) {
            send(exchange, service.advanceClock(Json.string(body, "duration")));
        } else if ("GET".equals(method) && path.equals("/api/resources")) {
            send(exchange, service.listResources());
        } else if ("POST".equals(method) && path.equals("/api/resources")) {
            send(exchange, 201, service.createResource(body, at));
        } else if ("GET".equals(method) && path.matches("/api/resources/[^/]+")) {
            send(exchange, service.getResource(lastSegment(path)));
        } else if ("PUT".equals(method) && path.matches("/api/resources/[^/]+")) {
            send(exchange, service.updateResource(lastSegment(path), body, at));
        } else if ("GET".equals(method) && path.matches("/api/resources/[^/]+/trace")) {
            send(exchange, service.traceResource(segment(path, 2)));
        } else if ("GET".equals(method) && path.matches("/api/resources/[^/]+/holds")) {
            send(exchange, service.listHolds(segment(path, 2)));
        } else if ("POST".equals(method) && path.matches("/api/resources/[^/]+/holds")) {
            send(exchange, 201, service.addHold(segment(path, 2), body, at));
        } else if ("GET".equals(method) && path.equals("/api/holds")) {
            send(exchange, service.listHolds(query.get("resourceId")));
        } else if ("GET".equals(method) && path.equals("/api/policies")) {
            send(exchange, service.listPolicies());
        } else if ("POST".equals(method) && path.equals("/api/policies")) {
            send(exchange, 201, service.createPolicyVersion(body, at));
        } else if ("GET".equals(method) && path.matches("/api/policies/[^/]+")) {
            send(exchange, service.getPolicyVersion(lastSegment(path)));
        } else if ("GET".equals(method) && path.equals("/api/plans")) {
            send(exchange, service.listPlans());
        } else if ("POST".equals(method) && path.equals("/api/plans")) {
            send(exchange, 201, service.createPlan(body, at));
        } else if ("GET".equals(method) && path.matches("/api/plans/[^/]+")) {
            send(exchange, service.getPlan(lastSegment(path)));
        } else if ("POST".equals(method) && path.matches("/api/plans/[^/]+/approve")) {
            send(exchange, service.approvePlan(segment(path, 2), body));
        } else if ("POST".equals(method) && path.matches("/api/plans/[^/]+/pre-execution-checks")) {
            send(exchange, service.runPreExecutionChecks(segment(path, 2), body, at));
        } else if ("POST".equals(method) && path.matches("/api/plans/[^/]+/executions")) {
            send(exchange, service.runExecution(segment(path, 2), body, at));
        } else if ("GET".equals(method) && path.matches("/api/executions/[^/]+")) {
            send(exchange, service.getExecution(lastSegment(path)));
        } else if ("POST".equals(method) && path.matches("/api/plan-items/[^/]+/verify")) {
            send(exchange, service.verifyItem(segment(path, 2), body));
        } else {
            throw new ApiException(404, "unknown endpoint: " + method + " " + path);
        }
    }

    private Map<String, Object> readBody(HttpExchange exchange) throws IOException {
        try (InputStream input = exchange.getRequestBody()) {
            String text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            if (text.isBlank()) {
                return new Json.JObject();
            }
            return Json.parseObject(text);
        } catch (IllegalArgumentException exception) {
            throw new ApiException(400, "request body must be valid JSON: " + exception.getMessage());
        }
    }

    private Map<String, String> query(HttpExchange exchange) {
        Map<String, Object> raw = splitQuery(exchange.getRequestURI().getRawQuery());
        Map<String, String> result = new HashMap<>();
        raw.forEach((key, value) -> result.put(key, value == null ? null : String.valueOf(value)));
        return result;
    }

    private Map<String, Object> splitQuery(String rawQuery) {
        Map<String, Object> result = new HashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return result;
        }
        for (String pair : rawQuery.split("&")) {
            String[] parts = pair.split("=", 2);
            String key = decode(parts[0]);
            String value = parts.length == 1 ? "" : decode(parts[1]);
            result.put(key, value);
        }
        return result;
    }

    private String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private String segment(String path, int fromStart) {
        String[] parts = path.split("/");
        return parts[fromStart + 1];
    }

    private String lastSegment(String path) {
        String[] parts = path.split("/");
        return parts[parts.length - 1];
    }

    private void servePage(HttpExchange exchange) throws IOException {
        try (InputStream input = HttpServer.class.getResourceAsStream("/index.html")) {
            if (input == null) {
                sendError(exchange, 404, "page missing");
                return;
            }
            byte[] bytes = input.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        }
    }

    private void send(HttpExchange exchange, Object response) throws IOException {
        send(exchange, 200, response);
    }

    private void send(HttpExchange exchange, int status, Object response) throws IOException {
        byte[] bytes = Json.write(response).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private void sendError(HttpExchange exchange, int status, String message) throws IOException {
        sendError(exchange, status, message, Map.of());
    }

    private void sendError(HttpExchange exchange, int status, String message, Map<String, String> extra) throws IOException {
        Json.JObject response = new Json.JObject();
        response.put("error", Map.of("status", status, "message", message));
        response.putAll(extra);
        send(exchange, status, response);
    }
}
