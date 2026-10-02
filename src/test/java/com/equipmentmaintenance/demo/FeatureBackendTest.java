package com.equipmentmaintenance.demo;

import static com.equipmentmaintenance.demo.FeatureSupport.o;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FeatureBackendTest {
  @TempDir Path directory;
  static final Clock NOW = Clock.fixed(Instant.parse("2026-10-02T04:00:00Z"), ZoneOffset.UTC);

  DemoStore store() throws IOException {
    return new DemoStore(directory.resolve("state.json"), NOW);
  }

  JsonNode read(DemoStore s, String kind) {
    return s.read(kind, null, Map.of(), "admin");
  }

  JsonNode call(DemoStore s, String code, String id, JsonNode input) throws IOException {
    return s.feature(code, id, input, "admin", Map.of()).data();
  }

  String device(DemoStore s) {
    return read(s, "devices").get(0).path("id").asText();
  }

  String task(DemoStore s) {
    return read(s, "tasks").get(0).path("id").asText();
  }

  JsonNode records(DemoStore s, String kind) throws IOException {
    return call(s, "records", kind, o());
  }

  String token(DemoStore s, String actor) throws IOException {
    JsonNode credentials =
        DemoDomain.JSON.readTree(
            Files.readString(directory.resolve("state.json.credentials.json")));
    return call(
            s,
            "AUTH",
            null,
            o(
                "account",
                actor,
                "credential",
                credentials.path(actor).path("password").asText(),
                "provider",
                "password"))
        .path("token")
        .asText();
  }

  JsonNode activeOrder(DemoStore s) throws IOException {
    JsonNode order =
        s.mutate(
            "createOrder",
            null,
            o(
                "deviceId",
                device(s),
                "title",
                "功能测试",
                "description",
                "本地隔离数据",
                "priority",
                "normal"),
            "operator");
    order =
        s.mutate(
            "assignOrder",
            order.path("id").asText(),
            o("version", 1, "assigneeId", "technician"),
            "admin");
    return s.mutate(
        "acceptOrder",
        order.path("id").asText(),
        o("version", order.path("version").asInt()),
        "technician");
  }

  String file(DemoStore s, String type, byte[] bytes, String entityType, String entityId)
      throws IOException {
    String id =
        call(
                s,
                "FILE",
                null,
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
                    bytes.length))
            .path("uploadId")
            .asText();
    s.upload(id, bytes, type, "admin");
    return id;
  }

  byte[] png() throws IOException {
    BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    ImageIO.write(image, "png", output);
    return output.toByteArray();
  }

  @Test
  void localAuthenticationRejectsBadExpiredAndDisabledSessions() throws Exception {
    DemoStore s = store();
    String adminToken = token(s, "admin");
    assertEquals("admin", s.authenticate(adminToken));
    assertEquals(
        401,
        assertThrows(
                DemoException.class,
                () ->
                    call(
                        s,
                        "AUTH",
                        null,
                        o("account", "admin", "credential", "wrong", "provider", "password")))
            .status);
    String inspectorToken = token(s, "inspector");
    call(s, "account", "inspector", o("version", 1, "active", false));
    assertEquals(
        "INVALID_SESSION",
        assertThrows(DemoException.class, () -> s.authenticate(inspectorToken)).code);
    DemoStore later =
        new DemoStore(directory.resolve("state.json"), Clock.offset(NOW, Duration.ofHours(9)));
    assertEquals(
        401, assertThrows(DemoException.class, () -> later.authenticate(adminToken)).status);
    assertEquals(
        403,
        assertThrows(
                DemoException.class, () -> s.feature("INVENTORY", null, o(), "operator", Map.of()))
            .status);
  }

  @Test
  void inventoryIsIdempotentAndConcurrentIssuesCannotOverspend() throws Exception {
    DemoStore s = store();
    String order = activeOrder(s).path("id").asText();
    ObjectNode request =
        o(
            "requestId",
            "issue-1",
            "kind",
            "issue",
            "warehouseId",
            "warehouse-main",
            "orderId",
            order,
            "lines",
            List.of(o("partId", "part-bearing", "quantity", 3, "unit", "piece")));
    JsonNode first = call(s, "INVENTORY", null, request);
    assertEquals(first, call(s, "INVENTORY", null, request));
    assertEquals(1, records(s, "inventoryTransactions").size());
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      List<Callable<Integer>> jobs = new ArrayList<>();
      for (String key : List.of("issue-2", "issue-3"))
        jobs.add(
            () -> {
              try {
                call(
                    s,
                    "INVENTORY",
                    null,
                    o(
                        "requestId",
                        key,
                        "kind",
                        "issue",
                        "warehouseId",
                        "warehouse-main",
                        "orderId",
                        order,
                        "lines",
                        List.of(o("partId", "part-bearing", "quantity", 5, "unit", "piece"))));
                return 200;
              } catch (DemoException e) {
                return e.status;
              }
            });
      List<Future<Integer>> outcomes = pool.invokeAll(jobs);
      List<Integer> statuses = List.of(outcomes.get(0).get(), outcomes.get(1).get());
      assertTrue(statuses.contains(200));
      assertTrue(statuses.contains(409));
    } finally {
      pool.shutdownNow();
    }
    JsonNode balance = records(s, "inventory").get(0);
    assertEquals(2, balance.path("quantity").asInt());
    assertEquals(
        409,
        assertThrows(
                DemoException.class,
                () ->
                    call(
                        s,
                        "INVENTORY",
                        null,
                        o(
                            "requestId",
                            "return-large",
                            "kind",
                            "return",
                            "warehouseId",
                            "warehouse-main",
                            "orderId",
                            order,
                            "lines",
                            List.of(o("partId", "part-bearing", "quantity", 9, "unit", "piece")))))
            .status);
    assertEquals(2, records(s, "inventory").get(0).path("quantity").asInt());
  }

  @Test
  void bomRejectsCyclesAndLifecycleAppliesOnlyAfterApproval() throws Exception {
    DemoStore s = store();
    String a = device(s), b = read(s, "devices").get(1).path("id").asText();
    call(
        s,
        "BOM",
        a,
        o(
            "version",
            1,
            "children",
            List.of(o("deviceId", b, "quantity", 1)),
            "sparePartIds",
            List.of("part-bearing")));
    assertEquals(
        400,
        assertThrows(
                DemoException.class,
                () ->
                    call(
                        s,
                        "BOM",
                        b,
                        o(
                            "version",
                            1,
                            "children",
                            List.of(o("deviceId", a, "quantity", 1)),
                            "sparePartIds",
                            List.of())))
            .status);
    String before = read(s, "devices").get(0).path("location").asText();
    JsonNode request =
        call(
            s,
            "ASSET",
            a,
            o(
                "version",
                2,
                "action",
                "transfer",
                "targetLocation",
                "新车间",
                "reason",
                "本地测试",
                "attachments",
                List.of()));
    assertEquals(before, read(s, "devices").get(0).path("location").asText());
    call(
        s,
        "lifecycleDecision",
        request.path("requestId").asText(),
        o("version", 1, "decision", "approve", "note", "同意"));
    assertEquals("新车间", read(s, "devices").get(0).path("location").asText());
  }

  @Test
  void telemetryDeduplicatesOrdersAndRecognizesOutOfOrder() throws Exception {
    DemoStore s = store();
    String device = device(s);
    int original = read(s, "orders").size();
    ObjectNode packet =
        o(
            "deviceId",
            device,
            "timestamp",
            NOW.instant().toString(),
            "metrics",
            o("temperature", 90, "vibration", 3),
            "eventId",
            "event-1");
    assertFalse(call(s, "IOT", null, packet).path("duplicate").asBoolean());
    assertTrue(call(s, "IOT", null, packet).path("duplicate").asBoolean());
    assertEquals(original + 1, read(s, "orders").size());
    assertTrue(
        call(
                s,
                "IOT",
                null,
                o(
                    "deviceId",
                    device,
                    "timestamp",
                    NOW.instant().minusSeconds(60).toString(),
                    "metrics",
                    o("temperature", 20, "vibration", 1),
                    "eventId",
                    "old"))
            .path("outOfOrder")
            .asBoolean());
    assertEquals("active", records(s, "alarms").get(0).path("status").asText());
    call(
        s,
        "IOT",
        null,
        o(
            "deviceId",
            device,
            "timestamp",
            NOW.instant().plusSeconds(1).toString(),
            "metrics",
            o("temperature", 20, "vibration", 1),
            "eventId",
            "recovery"));
    assertEquals("cleared", records(s, "alarms").get(0).path("status").asText());
  }

  @Test
  void schedulingComplianceAnalyticsWorkflowAndKnowledgeUseRealInputs() throws Exception {
    DemoStore s = store();
    JsonNode plan = read(s, "plans").get(0);
    JsonNode preview =
        call(
            s,
            "SCHEDULE",
            null,
            o(
                "planIds",
                List.of(plan.path("id").asText()),
                "horizon",
                o("from", "2026-10-02", "to", "2026-10-09"),
                "triggers",
                List.of("calendar", "runtime"),
                "resourceIds",
                List.of()));
    assertTrue(preview.path("proposedTasks").size() > 0);
    assertTrue(preview.path("conflicts").size() > 0);
    JsonNode compliance =
        call(
            s,
            "COMPLIANCE",
            null,
            o(
                "deviceId",
                device(s),
                "dueDate",
                "2026-10-10",
                "certificateId",
                "certificate-demo",
                "standardVersion",
                "standard-demo"));
    assertTrue(compliance.path("certificateValid").asBoolean());
    JsonNode metric =
        call(
            s,
            "COST",
            null,
            o(
                "period",
                o("from", "2026-10-01", "to", "2026-10-02"),
                "metrics",
                List.of("MTBF", "MTTR", "OEE"),
                "dimensions",
                List.of()));
    assertTrue(metric.path("metrics").path("OEE").isNull());
    assertEquals(3, metric.path("missingInputs").size());
    assertEquals(
        1,
        s.feature("KNOWLEDGE", null, o(), "admin", Map.of("q", "异响")).data().path("total").asInt());
    JsonNode workflow =
        call(
            s,
            "WORKFLOW",
            null,
            o(
                "name",
                "审批定义",
                "version",
                1,
                "nodes",
                List.of(
                    o("id", "start", "kind", "start", "next", List.of("end")),
                    o("id", "end", "kind", "end", "next", List.of())),
                "rules",
                o("assignment", "manual", "timeoutMinutes", 60)));
    assertTrue(workflow.path("validation").path("valid").asBoolean());
  }

  @Test
  void procurementReceiptsReportsAndLocalImportAreAtomic() throws Exception {
    DemoStore s = store();
    String request =
        call(
                s,
                "PROCUREMENT",
                null,
                o(
                    "supplierId",
                    "supplier-demo",
                    "reason",
                    "补库",
                    "lines",
                    List.of(o("partId", "part-bearing", "quantity", 2)),
                    "contractId",
                    "contract-demo"))
            .path("requestId")
            .asText();
    call(s, "procurementAction", request, o("version", 1, "action", "approve"));
    call(
        s,
        "procurementAction",
        request,
        o("version", 2, "action", "receive", "warehouseId", "warehouse-main"));
    assertEquals(12, records(s, "inventory").get(0).path("quantity").asInt());
    JsonNode report =
        call(
            s,
            "REPORT",
            null,
            o("reportType", "devices", "filters", o(), "format", "csv", "templateId", "local"));
    String fileId = report.path("downloadUrl").asText().split("/")[2];
    assertTrue(
        new String(s.download(fileId, "admin").bytes(), StandardCharsets.UTF_8).contains("EQ-001"));
    ObjectNode job =
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
            "import-1",
            "records",
            List.of(o("code", "IM-1", "name", "导入设备", "location", "车间", "category", "设备")));
    assertEquals(call(s, "INTEGRATION", null, job), call(s, "INTEGRATION", null, job));
    assertEquals(4, read(s, "devices").size());
  }

  @Test
  void filesValidateBytesAndGeneratedQrActuallyDecodes() throws Exception {
    DemoStore s = store();
    String source = file(s, "image/png", png(), "device", device(s));
    assertArrayEquals(png(), s.download(source, "admin").bytes());
    byte[] hugeImage = png();
    java.nio.ByteBuffer.wrap(hugeImage, 16, 4).putInt(10000);
    java.util.zip.CRC32 crc = new java.util.zip.CRC32();
    crc.update(hugeImage, 12, 17);
    java.nio.ByteBuffer.wrap(hugeImage, 29, 4).putInt((int) crc.getValue());
    assertEquals(
        400,
        assertThrows(DemoException.class, () -> FileVault.content("image/png", hugeImage)).status);
    assertEquals(
        400,
        assertThrows(
                DemoException.class,
                () ->
                    s.upload(source, "bad".getBytes(StandardCharsets.UTF_8), "image/png", "admin"))
            .status);
    JsonNode labels =
        call(
            s,
            "LABEL",
            null,
            o(
                "entityType",
                "device",
                "entityIds",
                List.of(device(s)),
                "format",
                "qr",
                "templateId",
                "local"));
    JsonNode label = labels.path("labels").get(0);
    String id = label.path("downloadUrl").asText().split("/")[2];
    String svg = new String(s.download(id, "admin").bytes(), StandardCharsets.UTF_8);
    int[] pixels = new int[256 * 256];
    Arrays.fill(pixels, 0xffffff);
    Matcher matcher = Pattern.compile("M(\\d+) (\\d+)h1v1h-1z").matcher(svg);
    while (matcher.find())
      pixels[Integer.parseInt(matcher.group(2)) * 256 + Integer.parseInt(matcher.group(1))] = 0;
    String decoded =
        new QRCodeReader()
            .decode(
                new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(256, 256, pixels))),
                Map.of(DecodeHintType.PURE_BARCODE, true))
            .getText();
    assertEquals(label.path("payload").asText(), decoded);
  }

  @Test
  void sopCannotSkipStepsAndBlocksTaskCompletionUntilFinished() throws Exception {
    DemoStore s = store();
    String task = task(s);
    JsonNode initial =
        call(
            s,
            "SOP",
            null,
            o(
                "taskId",
                task,
                "templateVersion",
                "1",
                "steps",
                List.of(o("stepId", "record", "checked", true, "evidenceIds", List.of()))));
    JsonNode info = s.read("task", task, Map.of(), "admin");
    List<ObjectNode> results = new ArrayList<>();
    for (JsonNode item : info.path("checklist"))
      results.add(o("itemId", item.path("id"), "verdict", "normal", "reading", "", "remark", ""));
    ObjectNode complete = o("version", 1, "results", results, "note", "");
    assertEquals(
        "SOP_INCOMPLETE",
        assertThrows(DemoException.class, () -> s.mutate("completeTask", task, complete, "admin"))
            .code);
    call(
        s,
        "SOP",
        null,
        o(
            "taskId",
            task,
            "templateVersion",
            "1",
            "version",
            initial.path("version"),
            "steps",
            List.of(
                o("stepId", "record", "checked", true, "evidenceIds", List.of()),
                o("stepId", "confirm", "checked", true, "evidenceIds", List.of()))));
    assertEquals(
        "completed", s.mutate("completeTask", task, complete, "admin").path("status").asText());
  }

  @Test
  void signedSnapshotIsImmutableAndLocationNeverClaimsPhysicalProof() throws Exception {
    DemoStore s = store();
    JsonNode order = activeOrder(s);
    String evidence =
        file(
            s,
            "text/plain",
            "evidence".getBytes(StandardCharsets.UTF_8),
            "order",
            order.path("id").asText());
    JsonNode signature =
        call(
            s,
            "SIGN",
            null,
            o(
                "entityType",
                "order",
                "entityId",
                order.path("id"),
                "version",
                order.path("version"),
                "signerId",
                "admin",
                "provider",
                "local",
                "evidenceId",
                evidence));
    String id = signature.path("archivedUrl").asText().split("/")[2];
    JsonNode archive = DemoDomain.JSON.readTree(s.download(id, "admin").bytes());
    assertEquals(signature.path("documentHash"), archive.path("documentHash"));
    String task = task(s),
        device = s.read("task", task, Map.of(), "admin").path("deviceId").asText();
    call(
        s,
        "devicePosition",
        device,
        o("version", 1, "latitude", 31.2, "longitude", 121.4, "radiusMeters", 50));
    JsonNode label =
        call(
                s,
                "LABEL",
                null,
                o(
                    "entityType",
                    "device",
                    "entityIds",
                    List.of(device),
                    "format",
                    "qr",
                    "templateId",
                    "local"))
            .path("labels")
            .get(0);
    JsonNode checkin =
        call(
            s,
            "LOCATION",
            null,
            o(
                "taskId",
                task,
                "deviceId",
                device,
                "position",
                o("latitude", 31.2, "longitude", 121.4, "accuracy", 5),
                "proof",
                label.path("id")));
    assertTrue(checkin.path("matched").asBoolean());
    assertFalse(checkin.path("physicalLocationVerified").asBoolean());
  }

  @Test
  void toolLoansAndCollaborationRejectDuplicatesAndUnauthorizedReaders() throws Exception {
    DemoStore s = store();
    String task = task(s);
    JsonNode loan =
        call(
            s,
            "TOOL",
            null,
            o(
                "toolId",
                "tool-meter",
                "borrowerId",
                "admin",
                "dueAt",
                NOW.instant().plusSeconds(3600).toString(),
                "taskId",
                task));
    assertEquals(
        409,
        assertThrows(
                DemoException.class,
                () ->
                    call(
                        s,
                        "TOOL",
                        null,
                        o(
                            "toolId",
                            "tool-meter",
                            "borrowerId",
                            "admin",
                            "dueAt",
                            NOW.instant().plusSeconds(3600).toString(),
                            "taskId",
                            task)))
            .status);
    call(s, "toolReturn", loan.path("loanId").asText(), o("version", 1, "note", "归还"));
    String order = read(s, "orders").get(0).path("id").asText();
    JsonNode room =
        call(
            s,
            "COLLAB",
            null,
            o("orderId", order, "participantIds", List.of("operator"), "mode", "text"));
    String id = room.path("roomId").asText();
    call(s, "roomMessage", id, o("message", "本地协作"));
    assertEquals(1, call(s, "roomMessages", id, o()).size());
    assertEquals(
        403,
        assertThrows(
                DemoException.class,
                () -> s.feature("roomMessages", id, o(), "inspector", Map.of()))
            .status);
  }

  @Test
  void trainingModelsAndOcrHaveHonestLocalResults() throws Exception {
    DemoStore s = store();
    JsonNode enrollment =
        call(
            s,
            "TRAINING",
            null,
            o("courseId", "course-demo", "employeeId", "operator", "certificationId", "local"));
    assertEquals(
        "qualified",
        call(
                s,
                "trainingComplete",
                enrollment.path("enrollmentId").asText(),
                o("version", 1, "answers", o("q1", "yes")))
            .path("qualificationStatus")
            .asText());
    String device = device(s),
        gltf =
            file(
                s,
                "model/gltf+json",
                "{\"asset\":{\"version\":\"2.0\"}}".getBytes(StandardCharsets.UTF_8),
                "device",
                device);
    JsonNode model =
        call(
            s,
            "catalogCreate",
            "models",
            o(
                "deviceId",
                device,
                "fileId",
                gltf,
                "name",
                "测试模型",
                "parts",
                List.of(),
                "instructions",
                List.of()));
    assertTrue(
        call(s, "AR3D", model.path("id").asText(), o()).path("modelUrl").asText().contains(gltf));
    String task = task(s), image = file(s, "image/png", png(), "task", task);
    JsonNode item = s.read("task", task, Map.of(), "admin").path("checklist").get(0);
    JsonNode reading =
        call(
            s,
            "OCR",
            null,
            o("taskId", task, "itemId", item.path("id"), "fileId", image, "meterType", "sample"));
    assertTrue(reading.path("value").isNull());
    assertEquals("manual_required", reading.path("status").asText());
    assertEquals(
        "confirmed_manually",
        call(
                s,
                "ocrConfirm",
                reading.path("readingId").asText(),
                o("version", 1, "value", 1.2, "unit", "bar"))
            .path("status")
            .asText());
  }

  @Test
  void offlineDuplicatesDoNotCreateSecondOrderAndExternalProvidersNeverReportSuccess()
      throws Exception {
    DemoStore s = store();
    int count = read(s, "orders").size();
    ObjectNode operation =
        o(
            "operationId",
            "op1",
            "entityId",
            "",
            "action",
            "createOrder",
            "payload",
            o(
                "deviceId",
                device(s),
                "title",
                "离线补单",
                "description",
                "实际本地创建",
                "priority",
                "normal"));
    ObjectNode request =
        o("clientId", "client1", "baseVersion", 1, "operations", List.of(operation));
    call(s, "OFFLINE", null, request);
    call(s, "OFFLINE", null, request);
    assertEquals(count + 1, read(s, "orders").size());
    assertEquals(
        503,
        s.feature(
                "MOBILE",
                null,
                o("platform", "wechat", "authorizationCode", "not-a-real-code", "appVersion", "1"),
                "admin",
                Map.of())
            .status());
    assertEquals(
        "[redacted]",
        records(s, "integrationJobs").get(0).path("request").path("authorizationCode").asText());
    assertEquals(
        503,
        s.feature(
                "AI",
                null,
                o(
                    "kind",
                    "prediction",
                    "deviceId",
                    device(s),
                    "datasetVersion",
                    "1",
                    "modelVersion",
                    "1",
                    "inputs",
                    o()),
                "admin",
                Map.of())
            .status());
    JsonNode push =
        o(
            "eventId",
            "push1",
            "recipientIds",
            List.of("operator"),
            "channels",
            List.of("wechat"),
            "templateId",
            "local",
            "variables",
            o());
    assertEquals(503, s.feature("PUSH", null, push, "admin", Map.of()).status());
    assertEquals(503, s.feature("PUSH", null, push, "admin", Map.of()).status());
    assertEquals(1, records(s, "deliveries").size());
  }

  @Test
  void settingsRejectExecutableFieldsAndBackupRestoresDataAndAttachments() throws Exception {
    DemoStore s = store();
    assertEquals(
        400,
        assertThrows(
                DemoException.class,
                () ->
                    call(
                        s,
                        "SETTINGS",
                        null,
                        o(
                            "version",
                            1,
                            "dictionaries",
                            o(),
                            "messageTemplates",
                            List.of(),
                            "mobileForms",
                            List.of(
                                o(
                                    "id",
                                    "bad",
                                    "fields",
                                    List.of(
                                        o(
                                            "id",
                                            "script",
                                            "label",
                                            "代码",
                                            "type",
                                            "script",
                                            "required",
                                            true)))))))
            .status);
    String attachment =
        file(s, "text/plain", "before".getBytes(StandardCharsets.UTF_8), "device", device(s));
    String session = token(s, "admin");
    JsonNode backup =
        call(
            s,
            "BACKUP",
            null,
            o("kind", "backup", "scope", "database-and-files", "restorePointId", null));
    s.mutate(
        "createDevice",
        null,
        o("code", "AFTER", "name", "恢复后应消失", "location", "位置", "category", "类型"),
        "admin");
    assertEquals(4, read(s, "devices").size());
    call(
        s,
        "BACKUP",
        null,
        o(
            "kind",
            "restore",
            "scope",
            "database-and-files",
            "restorePointId",
            backup.path("restorePointId"),
            "confirm",
            true));
    assertEquals(3, read(s, "devices").size());
    assertEquals(
        "before", new String(s.download(attachment, "admin").bytes(), StandardCharsets.UTF_8));
    assertEquals(401, assertThrows(DemoException.class, () -> s.authenticate(session)).status);
    JsonNode audit =
        call(
            s,
            "SECURITY",
            null,
            o(
                "from",
                NOW.instant().minusSeconds(60).toString(),
                "to",
                NOW.instant().plusSeconds(60).toString(),
                "entityType",
                "all",
                "reason",
                "本地回归"));
    assertEquals(64, audit.path("signedDigest").asText().length());
  }
}
