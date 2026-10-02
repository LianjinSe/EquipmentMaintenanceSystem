package com.equipmentmaintenance.demo;

import static com.equipmentmaintenance.demo.FeatureSupport.o;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;

/** Opt-in real Tomcat test. Always use an isolated EQUIPMENT_DEMO_DATA file. */
class LocalBackendHttpTest {
  private String base, token, password;
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private final Set<String> covered = new HashSet<>();

  private JsonNode request(String method, String path, JsonNode payload, int expected)
      throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(15));
    if (token != null) builder.header("Authorization", "Bearer " + token);
    if (payload != null) builder.header("Content-Type", "application/json");
    builder.method(
        method,
        payload == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofByteArray(DemoDomain.JSON.writeValueAsBytes(payload)));
    HttpResponse<byte[]> response =
        client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    JsonNode body = DemoDomain.JSON.readTree(response.body());
    assertEquals(expected, response.statusCode(), method + " " + path + " => " + body);
    return expected < 400 ? body.path("data") : body.path("error");
  }

  private JsonNode feature(String code, String method, String path, JsonNode payload, int expected)
      throws Exception {
    covered.add(code);
    return request(method, path, payload, expected);
  }

  private JsonNode get(String path) throws Exception {
    return request("GET", path, null, 200);
  }

  private JsonNode post(String path, JsonNode input, int status) throws Exception {
    return request("POST", path, input, status);
  }

  private byte[] download(String relative) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(base + relative))
            .header("Authorization", "Bearer " + token)
            .build();
    HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
    assertEquals(200, response.statusCode());
    return response.body();
  }

  private String upload(String entityType, String entityId, String type, byte[] bytes)
      throws Exception {
    JsonNode reservation =
        feature(
            "FILE",
            "POST",
            "api/files/uploads",
            o(
                "entityType",
                entityType,
                "entityId",
                entityId,
                "filename",
                "test.data",
                "contentType",
                type,
                "size",
                bytes.length),
            201);
    HttpRequest upload =
        HttpRequest.newBuilder(URI.create(base + reservation.path("uploadUrl").asText()))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", type)
            .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
            .build();
    HttpResponse<byte[]> response = client.send(upload, HttpResponse.BodyHandlers.ofByteArray());
    assertEquals(200, response.statusCode(), new String(response.body()));
    return reservation.path("uploadId").asText();
  }

  @Test
  void everyFormerlyReservedContractRunsThroughRealServletAndPersistence() throws Exception {
    base = System.getProperty("demo.test.baseUrl");
    String credentialsPath = System.getProperty("demo.test.credentials");
    Assumptions.assumeTrue(
        base != null && credentialsPath != null,
        "Real Tomcat test requires demo.test.baseUrl and demo.test.credentials");
    if (!base.endsWith("/")) base += "/";
    assertEquals(64, get("api/health").path("backendFingerprint").asText().length());
    password =
        DemoDomain.JSON
            .readTree(Files.readString(Path.of(credentialsPath)))
            .path("admin")
            .path("password")
            .asText();
    token =
        feature(
                "AUTH",
                "POST",
                "api/auth/login",
                o("account", "admin", "credential", password, "provider", "password"),
                200)
            .path("token")
            .asText();
    String adminToken = token;
    token = null;
    request("GET", "api/catalog", null, 401);
    token = "invalid-token";
    request("GET", "api/catalog", null, 401);
    token = adminToken;
    post(
        "api/auth/login",
        o("account", "admin", "credential", "invalid-password", "provider", "password"),
        401);
    HttpResponse<String> crossOrigin =
        client.send(
            HttpRequest.newBuilder(URI.create(base + "api/auth/login"))
                .header("Origin", "http://attacker.example")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(403, crossOrigin.statusCode());
    JsonNode state = get("api/state"), catalog = get("api/catalog");
    String today = state.path("summary").path("today").asText();
    String stamp = UUID.randomUUID().toString();
    String device = state.path("devices").get(0).path("id").asText(),
        child = state.path("devices").get(1).path("id").asText(),
        task = state.path("tasks").get(0).path("id").asText(),
        taskDevice = state.path("tasks").get(0).path("deviceId").asText();
    assertFalse(
        state.has("features"), "Legacy UI snapshots must not expose account/session hashes");
    JsonNode credentials = DemoDomain.JSON.readTree(Files.readString(Path.of(credentialsPath)));
    token =
        post(
                "api/auth/login",
                o(
                    "account",
                    "operator",
                    "credential",
                    credentials.path("operator").path("password").asText(),
                    "provider",
                    "password"),
                200)
            .path("token")
            .asText();
    post("api/inventory/transactions", o(), 403);
    token = adminToken;
    JsonNode role = catalog.path("roles").get(1);
    feature(
        "ACCESS",
        "PUT",
        "api/access/roles/operator",
        o(
            "version",
            1,
            "name",
            "操作工本地角色",
            "menus",
            List.of(),
            "operations",
            List.of(
                "FILE",
                "LABEL",
                "KNOWLEDGE",
                "MOBILE",
                "OFFLINE",
                "SOP",
                "SIGN",
                "LOCATION",
                "OCR",
                "TOOL",
                "COLLAB",
                "AR3D",
                "TRAINING"),
            "dataScope",
            o("departmentIds", List.of("生产一部")),
            "fields",
            List.of()),
        200);
    feature(
        "SETTINGS",
        "PUT",
        "api/settings",
        o(
            "version",
            1,
            "dictionaries",
            o("iotThresholds", o("temperature", 80, "vibration", 10)),
            "messageTemplates",
            List.of(),
            "mobileForms",
            List.of()),
        200);
    JsonNode asset =
        feature(
            "ASSET",
            "POST",
            "api/devices/" + device + "/lifecycle",
            o(
                "version",
                1,
                "action",
                "transfer",
                "targetLocation",
                "HTTP验收位置",
                "reason",
                "本地隔离测试",
                "attachments",
                List.of()),
            201);
    post(
        "api/lifecycle/" + asset.path("requestId").asText() + "/decision",
        o("version", 1, "decision", "approve", "note", "本地批准"),
        200);
    feature(
        "BOM",
        "PUT",
        "api/devices/" + device + "/bom",
        o(
            "version",
            2,
            "children",
            List.of(o("deviceId", child, "quantity", 1)),
            "sparePartIds",
            List.of("part-bearing")),
        200);
    String certDevice = catalog.path("certificates").get(0).path("deviceId").asText();
    feature(
        "COMPLIANCE",
        "POST",
        "api/compliance/inspections",
        o(
            "deviceId",
            certDevice,
            "dueDate",
            LocalDate.parse(today).plusDays(7).toString(),
            "certificateId",
            "certificate-demo",
            "standardVersion",
            "standard-demo"),
        201);
    ObjectNode telemetry =
        o(
            "deviceId",
            device,
            "timestamp",
            Instant.now().toString(),
            "metrics",
            o("temperature", 90, "vibration", 2),
            "eventId",
            "http-iot-" + stamp);
    feature("IOT", "POST", "api/iot/telemetry", telemetry, 200);
    assertTrue(post("api/iot/telemetry", telemetry, 200).path("duplicate").asBoolean());
    feature(
        "SCHEDULE",
        "POST",
        "api/scheduling/preview",
        o(
            "planIds",
            List.of(state.path("plans").get(0).path("id").asText()),
            "horizon",
            o("from", today, "to", LocalDate.parse(today).plusDays(7).toString()),
            "triggers",
            List.of("calendar"),
            "resourceIds",
            List.of()),
        200);
    JsonNode workflow =
        feature(
            "WORKFLOW",
            "POST",
            "api/workflows/definitions",
            o(
                "name",
                "HTTP流程",
                "version",
                1,
                "nodes",
                List.of(
                    o("id", "start", "kind", "start", "next", List.of("end")),
                    o("id", "end", "kind", "end", "next", List.of())),
                "rules",
                o("assignment", "manual", "timeoutMinutes", 60)),
            201);
    JsonNode metrics =
        feature(
            "COST",
            "POST",
            "api/analytics/calculate",
            o(
                "period",
                o("from", today, "to", today),
                "metrics",
                List.of("MTTR", "OEE"),
                "dimensions",
                List.of()),
            200);
    assertTrue(metrics.path("metrics").path("OEE").isNull());
    assertTrue(
        feature("KNOWLEDGE", "GET", "api/knowledge/search?q=%E5%BC%82%E5%93%8D", null, 200)
                .path("total")
                .asInt()
            > 0);
    JsonNode order =
        post(
            "api/orders",
            o(
                "deviceId",
                device,
                "title",
                "HTTP完整流程",
                "description",
                "本地接口验收",
                "priority",
                "normal"),
            201);
    String orderId = order.path("id").asText();
    post("api/orders/" + orderId + "/assign", o("version", 1, "assigneeId", "technician"), 200);
    post("api/orders/" + orderId + "/accept", o("version", 2), 200);
    ObjectNode issue =
        o(
            "requestId",
            "http-issue-" + stamp,
            "kind",
            "issue",
            "warehouseId",
            "warehouse-main",
            "orderId",
            orderId,
            "lines",
            List.of(o("partId", "part-bearing", "quantity", 1, "unit", "piece")));
    JsonNode txn = feature("INVENTORY", "POST", "api/inventory/transactions", issue, 200);
    assertEquals(txn, post("api/inventory/transactions", issue, 200));
    JsonNode purchase =
        feature(
            "PROCUREMENT",
            "POST",
            "api/procurement/requests",
            o(
                "supplierId",
                "supplier-demo",
                "reason",
                "补库",
                "lines",
                List.of(o("partId", "part-bearing", "quantity", 1)),
                "contractId",
                "contract-demo"),
            201);
    String purchaseId = purchase.path("requestId").asText();
    post("api/procurement/" + purchaseId + "/action", o("version", 1, "action", "approve"), 200);
    post(
        "api/procurement/" + purchaseId + "/action",
        o("version", 2, "action", "receive", "warehouseId", "warehouse-main"),
        200);
    JsonNode report =
        feature(
            "REPORT",
            "POST",
            "api/reports/export",
            o("reportType", "orders", "filters", o(), "format", "csv", "templateId", "local"),
            200);
    assertTrue(download(report.path("downloadUrl").asText()).length > 20);
    feature(
        "INTEGRATION",
        "POST",
        "api/integrations/jobs",
        o(
            "system",
            "local",
            "direction",
            "import",
            "entity",
            "equipment",
            "cursor",
            "",
            "requestId",
            "http-import-" + stamp,
            "records",
            List.of(
                o(
                    "code",
                    "HTTP-" + stamp.substring(0, 8),
                    "name",
                    "HTTP导入",
                    "location",
                    "本地",
                    "category",
                    "测试"))),
        200);
    feature(
        "OUTSOURCE",
        "POST",
        "api/outsource/orders",
        o(
            "orderId",
            orderId,
            "supplierId",
            "supplier-demo",
            "contractId",
            "contract-demo",
            "expectedReturnDate",
            LocalDate.parse(today).plusDays(7).toString()),
        201);
    BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    ImageIO.write(image, "png", bytes);
    String imageId = upload("order", orderId, "image/png", bytes.toByteArray());
    assertArrayEquals(bytes.toByteArray(), download("api/files/" + imageId + "/content"));
    JsonNode labels =
        feature(
            "LABEL",
            "POST",
            "api/labels/generate",
            o(
                "entityType",
                "device",
                "entityIds",
                List.of(taskDevice),
                "format",
                "qr",
                "templateId",
                "local"),
            200);
    assertTrue(download(labels.path("labels").get(0).path("downloadUrl").asText()).length > 100);
    assertEquals(
        "PROVIDER_NOT_CONFIGURED",
        feature(
                "MOBILE",
                "POST",
                "api/mobile/session",
                o(
                    "platform",
                    "wechat",
                    "authorizationCode",
                    "local-test-not-real",
                    "appVersion",
                    "1"),
                503)
            .path("code")
            .asText());
    feature(
        "OFFLINE",
        "POST",
        "api/offline/sync",
        o(
            "clientId",
            "http-client",
            "baseVersion",
            1,
            "operations",
            List.of(
                o(
                    "operationId",
                    "http-op-" + stamp,
                    "entityId",
                    "",
                    "action",
                    "createOrder",
                    "payload",
                    o(
                        "deviceId",
                        device,
                        "title",
                        "HTTP同步工单",
                        "description",
                        "幂等同步",
                        "priority",
                        "normal")))),
        200);
    feature(
        "SOP",
        "POST",
        "api/sop/executions",
        o(
            "taskId",
            task,
            "templateVersion",
            "1",
            "steps",
            List.of(
                o("stepId", "record", "checked", true, "evidenceIds", List.of()),
                o("stepId", "confirm", "checked", true, "evidenceIds", List.of()))),
        200);
    JsonNode signature =
        feature(
            "SIGN",
            "POST",
            "api/signatures",
            o(
                "entityType",
                "order",
                "entityId",
                orderId,
                "version",
                3,
                "signerId",
                "admin",
                "provider",
                "local",
                "evidenceId",
                imageId),
            201);
    assertTrue(download(signature.path("archivedUrl").asText()).length > 0);
    JsonNode now = get("api/state");
    JsonNode located = null;
    for (JsonNode candidate : now.path("devices"))
      if (candidate.path("id").asText().equals(taskDevice)) located = candidate;
    assertNotNull(located);
    request(
        "PUT",
        "api/devices/" + taskDevice + "/position",
        o(
            "version",
            located.path("version"),
            "latitude",
            31.2,
            "longitude",
            121.4,
            "radiusMeters",
            50),
        200);
    JsonNode location =
        feature(
            "LOCATION",
            "POST",
            "api/location/checkins",
            o(
                "taskId",
                task,
                "deviceId",
                taskDevice,
                "position",
                o("latitude", 31.2, "longitude", 121.4, "accuracy", 5),
                "proof",
                labels.path("labels").get(0).path("id")),
            201);
    assertFalse(location.path("physicalLocationVerified").asBoolean());
    JsonNode reading =
        feature(
            "OCR",
            "POST",
            "api/ocr/readings",
            o(
                "taskId",
                task,
                "itemId",
                state.path("tasks").get(0).path("checklist").get(0).path("id"),
                "fileId",
                imageId,
                "meterType",
                "local"),
            202);
    assertTrue(reading.path("value").isNull());
    post(
        "api/ocr/" + reading.path("readingId").asText() + "/confirm",
        o("version", 1, "value", 1.2, "unit", "bar"),
        200);
    JsonNode loan =
        feature(
            "TOOL",
            "POST",
            "api/tools/loans",
            o(
                "toolId",
                "tool-meter",
                "borrowerId",
                "admin",
                "dueAt",
                Instant.now().plusSeconds(3600).toString(),
                "taskId",
                task),
            201);
    post(
        "api/tools/loans/" + loan.path("loanId").asText() + "/return",
        o("version", 1, "note", "归还"),
        200);
    JsonNode room =
        feature(
            "COLLAB",
            "POST",
            "api/collaboration/rooms",
            o("orderId", orderId, "participantIds", List.of("operator"), "mode", "text"),
            201);
    post(
        "api/collaboration/rooms/" + room.path("roomId").asText() + "/messages",
        o("message", "HTTP协作测试"),
        201);
    assertEquals(1, get(room.path("joinUrl").asText()).size());
    String modelFile =
        upload(
            "device",
            device,
            "model/gltf+json",
            "{\"asset\":{\"version\":\"2.0\"}}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    JsonNode model =
        post(
            "api/catalog/models",
            o(
                "deviceId",
                device,
                "fileId",
                modelFile,
                "name",
                "HTTP模型",
                "parts",
                List.of(),
                "instructions",
                List.of()),
            201);
    feature("AR3D", "GET", "api/models/" + model.path("id").asText(), null, 200);
    JsonNode enrollment =
        feature(
            "TRAINING",
            "POST",
            "api/training/enrollments",
            o("courseId", "course-demo", "employeeId", "operator", "certificationId", "local"),
            201);
    post(
        "api/training/enrollments/" + enrollment.path("enrollmentId").asText() + "/complete",
        o("version", 1, "answers", o("q1", "yes")),
        200);
    JsonNode rule =
        feature(
            "AI",
            "POST",
            "api/intelligence/jobs",
            o(
                "kind",
                "threshold_evaluation",
                "deviceId",
                device,
                "datasetVersion",
                "local",
                "modelVersion",
                "rule",
                "inputs",
                o("metrics", o("temperature", 90), "thresholds", o("temperature", 80))),
            200);
    assertTrue(rule.path("result").path("temperature").asBoolean());
    feature(
        "LOWCODE",
        "POST",
        "api/customizations",
        o(
            "version",
            1,
            "forms",
            List.of(),
            "workflows",
            List.of(workflow.path("definitionId").asText()),
            "reports",
            List.of(),
            "locale",
            "zh-CN"),
        201);
    feature(
        "SECURITY",
        "POST",
        "api/security/audit-exports",
        o(
            "from",
            Instant.now().minusSeconds(3600).toString(),
            "to",
            Instant.now().plusSeconds(3600).toString(),
            "entityType",
            "all",
            "reason",
            "HTTP本地验收"),
        200);
    ObjectNode notification =
        o(
            "eventId",
            "http-push-" + stamp,
            "recipientIds",
            List.of("operator"),
            "channels",
            List.of("in_app"),
            "templateId",
            "local",
            "variables",
            o("title", "HTTP站内通知"));
    assertEquals(
        feature("PUSH", "POST", "api/notifications/deliveries", notification, 200),
        post("api/notifications/deliveries", notification, 200));
    int before = get("api/state").path("devices").size();
    JsonNode backup =
        feature(
            "BACKUP",
            "POST",
            "api/backups/jobs",
            o("kind", "backup", "scope", "database-and-files", "restorePointId", null),
            200);
    post(
        "api/devices",
        o(
            "code",
            "RESTORE-" + stamp.substring(0, 8),
            "name",
            "恢复回归",
            "location",
            "测试",
            "category",
            "测试"),
        201);
    post(
        "api/backups/jobs",
        o(
            "kind",
            "restore",
            "scope",
            "database-and-files",
            "restorePointId",
            backup.path("restorePointId"),
            "confirm",
            true),
        200);
    request("GET", "api/catalog", null, 401);
    token =
        post(
                "api/auth/login",
                o("account", "admin", "credential", password, "provider", "password"),
                200)
            .path("token")
            .asText();
    assertEquals(before, get("api/state").path("devices").size());
    assertArrayEquals(bytes.toByteArray(), download("api/files/" + imageId + "/content"));
    Set<String> expected = new HashSet<>();
    for (JsonNode capability : get("api/capabilities").path("implemented"))
      if (capability.has("id")) expected.add(capability.path("id").asText());
    assertEquals(expected, covered, "Every former reserved capability must be exercised");
    System.out.println(
        "HTTP_LOCAL_BACKEND_OK capabilities="
            + covered.size()
            + " routes="
            + get("api/capabilities").path("implemented").size());
  }
}
