package com.equipmentmaintenance.demo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Servlet endpoint for the existing browser UI and the explicit 501 reservation registry. */
public final class ApiServlet extends HttpServlet {
    private DemoStore store;
    private JsonNode capabilities;

    @Override
    public void init() throws ServletException {
        try (InputStream registry = getClass().getResourceAsStream("/capabilities.json")) {
            if (registry == null) throw new IOException("capabilities.json is missing from the WAR");
            capabilities = DemoDomain.JSON.readTree(registry);
            if (!capabilities.path("implemented").isArray() || !capabilities.path("reserved").isArray()) {
                throw new IOException("API registry is invalid");
            }
            store = new DemoStore(DemoStore.defaultPath(), Clock.systemUTC());
            getServletContext().log("Equipment demo data: " + store.file());
        } catch (IOException error) {
            throw new ServletException("演示数据或接口注册表加载失败；原数据文件未被覆盖", error);
        }
    }

    private record Match(JsonNode contract, String id) {}

    private Match match(String method, String path) {
        for (String group : new String[]{"implemented", "reserved"}) {
            for (JsonNode contract : capabilities.path(group)) {
                if (!method.equals(contract.path("method").asText())) continue;
                String rule = contract.path("path").asText().replace("{id}", "([^/]+)");
                Matcher candidate = Pattern.compile("^" + rule + "$").matcher(path);
                if (candidate.matches()) return new Match(contract, candidate.groupCount() > 0 ? candidate.group(1) : null);
            }
        }
        return null;
    }

    private static void writeJson(HttpServletResponse response, int status, JsonNode payload) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        DemoDomain.JSON.writeValue(response.getOutputStream(), payload);
    }

    private static JsonNode readBody(HttpServletRequest request) throws IOException {
        String type = request.getContentType();
        if (type == null || !type.toLowerCase().startsWith("application/json")) {
            throw new DemoException(415, "UNSUPPORTED_MEDIA_TYPE", "写入请求须使用 application/json");
        }
        byte[] bytes = request.getInputStream().readNBytes(512 * 1024 + 1);
        if (bytes.length > 512 * 1024) throw new DemoException(413, "PAYLOAD_TOO_LARGE", "请求体超过 512 KiB");
        try {
            return DemoDomain.JSON.readTree(bytes);
        } catch (JsonProcessingException error) {
            throw new DemoException(400, "INVALID_JSON", "JSON 请求体无法解析");
        }
    }

    private static Map<String, String> query(HttpServletRequest request) {
        Map<String, String> values = new HashMap<>();
        request.getParameterMap().forEach((name, entries) -> {
            if (entries.length > 0) values.put(name, entries[0]);
        });
        return values;
    }

    private void docs(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String path = switch (request.getServletPath()) {
            case "/docs/api" -> "/docs/API.md";
            case "/docs/unfinished" -> "/docs/未完成部分与实施说明.md";
            case "/docs/coverage" -> "/docs/功能覆盖矩阵.md";
            default -> null;
        };
        if (path == null || !"GET".equals(request.getMethod())) throw new DemoException(404, "NOT_FOUND", "页面不存在");
        try (InputStream input = getServletContext().getResourceAsStream(path)) {
            if (input == null) throw new DemoException(404, "NOT_FOUND", "说明文件不存在");
            response.setContentType("text/plain;charset=UTF-8");
            response.setHeader("Cache-Control", "no-cache");
            input.transferTo(response.getOutputStream());
        }
    }

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        try {
            if (request.getServletPath().startsWith("/docs/")) {
                docs(request, response);
                return;
            }
            String method = request.getMethod();
            String path = request.getServletPath() + (request.getPathInfo() == null ? "" : request.getPathInfo());
            Match selected = match(method, path);
            if (selected == null) throw new DemoException(404, "NOT_FOUND", "接口不存在；请查看 /api/capabilities");
            JsonNode contract = selected.contract();
            if (!contract.path("implemented").asBoolean()) {
                throw new DemoException(501, "NOT_IMPLEMENTED", contract.path("name").asText() + "尚未实现",
                        DemoDomain.object("capabilityId", contract.path("id").asText(),
                                "method", method, "path", contract.path("path").asText(),
                                "proposal", contract.path("request"), "dependencies", contract.path("dependencies"),
                                "acceptance", contract.path("acceptance"), "documentation", "docs/api"));
            }
            boolean write = !"GET".equals(method);
            String actorId = request.getHeader("X-Demo-Actor");
            if (actorId == null && !write) actorId = "admin";
            DemoDomain.actor(actorId);
            String origin = request.getHeader("Origin");
            if (write && origin != null && !origin.equals(request.getScheme() + "://" + request.getHeader("Host"))) {
                throw new DemoException(403, "ORIGIN_REJECTED", "不接受跨站写入");
            }
            String handler = contract.path("handler").asText();
            JsonNode result;
            if ("capabilities".equals(handler)) result = capabilities.deepCopy();
            else if (write) result = store.mutate(handler, selected.id(), readBody(request), actorId);
            else result = store.read(handler, selected.id(), query(request), actorId);
            int status = "POST".equals(method) && ("createDevice".equals(handler) || "createOrder".equals(handler) || "createPlan".equals(handler)) ? 201 : 200;
            writeJson(response, status, DemoDomain.object("data", result));
        } catch (DemoException error) {
            writeJson(response, error.status, DemoDomain.object("error", DemoDomain.object("code", error.code,
                    "message", error.getMessage(), "details", error.details)));
        } catch (Exception error) {
            getServletContext().log("Demo request failed", error);
            writeJson(response, 500, DemoDomain.object("error", DemoDomain.object("code", "INTERNAL_ERROR",
                    "message", "本地保存或服务异常；操作未提交，请查看 Tomcat 日志", "details", null)));
        }
    }
}
