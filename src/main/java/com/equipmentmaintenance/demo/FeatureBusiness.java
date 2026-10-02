package com.equipmentmaintenance.demo;

import static com.equipmentmaintenance.demo.FeatureSupport.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.google.zxing.*;
import com.google.zxing.common.BitMatrix;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

final class FeatureBusiness {
  static FeatureResult execute(
      ObjectNode state,
      String code,
      String id,
      JsonNode in,
      String actor,
      Map<String, String> query,
      Clock clock,
      FileVault vault)
      throws IOException {
    switch (code) {
      case "ASSET" -> {
        admin(actor);
        fields(in, "version", "action", "targetLocation", "reason", "attachments");
        ObjectNode device = core(state, "devices", id);
        version(device, in);
        String action = choice(in, "action", "transfer", "deactivate", "reactivate", "scrap");
        String reason = text(in, "reason", 500);
        if (action.equals("transfer")) text(in, "targetLocation", 80);
        for (JsonNode attachment : array(in, "attachments", 0, 20))
          uploaded(state, attachment.asText(), actor);
        ObjectNode request =
            add(
                state,
                "lifecycleRequests",
                o(
                    "deviceId",
                    id,
                    "deviceVersion",
                    device.path("version"),
                    "action",
                    action,
                    "targetLocation",
                    optional(in, "targetLocation", 80),
                    "reason",
                    reason,
                    "attachments",
                    in.get("attachments"),
                    "status",
                    "pending_approval"),
                actor,
                clock);
        return new FeatureResult(
            201,
            o(
                "requestId",
                request.path("id"),
                "status",
                request.path("status"),
                "deviceVersion",
                device.path("version")));
      }
      case "lifecycleDecision" -> {
        admin(actor);
        fields(in, "version", "decision", "note");
        ObjectNode request = ref(state, "lifecycleRequests", id);
        version(request, in);
        require(request.path("status").asText().equals("pending_approval"), "申请已处理");
        String decision = choice(in, "decision", "approve", "reject");
        text(in, "note", 500);
        ObjectNode device = core(state, "devices", request.path("deviceId").asText());
        if (decision.equals("approve")) {
          if (device.path("version").asInt() != request.path("deviceVersion").asInt())
            throw fail(409, "VERSION_CONFLICT", "设备在申请后已更新");
          String action = request.path("action").asText();
          if (action.equals("scrap")) {
            for (JsonNode order : state.path("orders"))
              require(
                  !order.path("deviceId").asText().equals(device.path("id").asText())
                      || Set.of("closed", "cancelled").contains(order.path("status").asText()),
                  "报废前须处理未关闭工单");
            for (JsonNode task : state.path("tasks"))
              require(
                  !task.path("deviceId").asText().equals(device.path("id").asText())
                      || task.path("status").asText().equals("completed"),
                  "报废前须处理待执行任务");
          }
          request.set("before", device.deepCopy());
          if (action.equals("transfer"))
            device.put("location", request.path("targetLocation").asText());
          else
            device.put(
                "lifecycleStatus",
                action.equals("scrap")
                    ? "scrapped"
                    : action.equals("deactivate") ? "inactive" : "active");
          if (Set.of("scrap", "deactivate").contains(action))
            for (JsonNode plan : state.path("plans"))
              if (plan.path("deviceId").asText().equals(device.path("id").asText())) {
                ((ObjectNode) plan).put("active", false);
                ((ObjectNode) plan).put("version", plan.path("version").asInt() + 1);
              }
          device.put("version", device.path("version").asInt() + 1);
          request.set("after", device.deepCopy());
        }
        request.put("status", decision.equals("approve") ? "approved" : "rejected");
        request.put("note", in.path("note").asText());
        request.put("version", request.path("version").asInt() + 1);
        audit(state, actor, "device", device.path("id").asText(), "lifecycle-" + decision, clock);
        return FeatureResult.ok(o("request", request, "device", device));
      }
      case "COMPLIANCE" -> {
        admin(actor);
        fields(in, "deviceId", "dueDate", "certificateId", "standardVersion");
        String deviceId = text(in, "deviceId", 80);
        core(state, "devices", deviceId);
        String due = date(in, "dueDate");
        ObjectNode standard = ref(state, "standards", text(in, "standardVersion", 80));
        require(standard.path("approved").asBoolean(), "标准尚未批准");
        ObjectNode cert = ref(state, "certificates", text(in, "certificateId", 80));
        require(
            cert.path("deviceId").asText().equals(deviceId)
                && cert.path("standardVersion").asText().equals(standard.path("id").asText()),
            "证书与设备/标准不匹配");
        boolean valid =
            cert.path("status").asText().equals("valid")
                && cert.path("validUntil").asText().compareTo(due) >= 0;
        ObjectNode inspection =
            add(
                state,
                "complianceInspections",
                o(
                    "deviceId",
                    deviceId,
                    "dueDate",
                    due,
                    "certificateId",
                    cert.path("id"),
                    "standardVersion",
                    standard.path("id"),
                    "status",
                    valid ? "scheduled" : "requires_renewal",
                    "reminders",
                    List.of(LocalDate.parse(due).minusDays(7).toString(), due)),
                actor,
                clock);
        message(
            state,
            actor,
            "admin",
            "检查计划：" + deviceId + " · " + due + (valid ? "" : " · 证书需更新"),
            "compliance",
            inspection.path("id").asText(),
            clock);
        return new FeatureResult(
            201,
            o(
                "inspectionId",
                inspection.path("id"),
                "status",
                inspection.path("status"),
                "reminders",
                inspection.path("reminders"),
                "certificateValid",
                valid));
      }
      case "BOM" -> {
        admin(actor);
        fields(in, "version", "children", "sparePartIds");
        ObjectNode device = core(state, "devices", id);
        version(device, in);
        ArrayNode children = array(in, "children", 0, 100);
        Set<String> unique = new HashSet<>();
        for (JsonNode child : children) {
          fields(child, "deviceId", "quantity");
          String childId = text(child, "deviceId", 80);
          core(state, "devices", childId);
          require(unique.add(childId) && !id.equals(childId), "重复或自引用设备关系");
          integer(child, "quantity", 1, 1000000);
        }
        for (JsonNode part : array(in, "sparePartIds", 0, 100)) ref(state, "parts", part.asText());
        Map<String, List<String>> graph = new HashMap<>();
        for (JsonNode bom : rows(state, "boms")) {
          List<String> edges = new ArrayList<>();
          for (JsonNode child : bom.path("children")) edges.add(child.path("deviceId").asText());
          graph.putIfAbsent(bom.path("deviceId").asText(), edges);
        }
        graph.put(id, new ArrayList<>(unique));
        cycle(graph, id, new HashSet<>(), new HashSet<>());
        ObjectNode record =
            add(
                state,
                "boms",
                o(
                    "deviceId",
                    id,
                    "children",
                    children,
                    "sparePartIds",
                    in.get("sparePartIds"),
                    "supersedesVersion",
                    device.path("version")),
                actor,
                clock);
        device.put("version", device.path("version").asInt() + 1);
        return FeatureResult.ok(o("tree", record, "version", device.path("version")));
      }
      case "IOT" -> {
        return FeatureResult.ok(telemetry(state, in, actor, clock));
      }
      case "SCHEDULE" -> {
        admin(actor);
        fields(in, "planIds", "horizon", "triggers", "resourceIds");
        fields(in.path("horizon"), "from", "to");
        String from = date(in.path("horizon"), "from"), to = date(in.path("horizon"), "to");
        require(to.compareTo(from) >= 0 && ChronoUnitDays(from, to) <= 90, "排程范围须为至多90天");
        ArrayNode conflicts = empty(), tasks = empty();
        Set<String> slots = new HashSet<>();
        for (JsonNode trigger : array(in, "triggers", 0, 5))
          if (!trigger.asText().equals("calendar"))
            conflicts.add(
                o("kind", "missing_input", "trigger", trigger, "reason", "本地仅预览日期计划，运行量触发未配置"));
        Set<String> resources = new HashSet<>();
        for (JsonNode resource : array(in, "resourceIds", 0, 20)) {
          DemoDomain.actor(resource.asText());
          resources.add(resource.asText());
        }
        ArrayNode ids = array(in, "planIds", 1, 50);
        for (JsonNode planId : ids) {
          ObjectNode plan = core(state, "plans", planId.asText());
          if (!plan.path("active").asBoolean()) continue;
          LocalDate due = LocalDate.parse(plan.path("nextDueDate").asText());
          while (due.isBefore(LocalDate.parse(from)))
            due = due.plusDays(plan.path("intervalDays").asInt());
          while (!due.isAfter(LocalDate.parse(to))) {
            String assignee = plan.path("assigneeId").asText();
            if (!resources.isEmpty() && !resources.contains(assignee))
              conflicts.add(
                  o("kind", "unavailable_resource", "planId", planId, "assigneeId", assignee));
            if (!slots.add(assignee + due))
              conflicts.add(
                  o("kind", "same_day_assignment", "assigneeId", assignee, "date", due.toString()));
            tasks.add(
                o(
                    "planId",
                    planId,
                    "deviceId",
                    plan.path("deviceId"),
                    "scheduledDate",
                    due.toString(),
                    "assigneeId",
                    assignee));
            due = due.plusDays(plan.path("intervalDays").asInt());
            require(tasks.size() <= 1000, "预览任务超过1000项");
          }
        }
        return FeatureResult.ok(
            o(
                "proposedTasks",
                tasks,
                "conflicts",
                conflicts,
                "explanation",
                "仅预览，不创建任务；同日资源冲突需要人工确认"));
      }
      case "WORKFLOW" -> {
        admin(actor);
        fields(in, "name", "version", "nodes", "rules");
        text(in, "name", 100);
        integer(in, "version", 1, Integer.MAX_VALUE);
        ArrayNode nodes = array(in, "nodes", 2, 30);
        Map<String, List<String>> graph = new HashMap<>();
        Set<String> ids = new HashSet<>();
        for (JsonNode node : nodes) {
          fields(node, "id", "kind", "next");
          String nodeId = text(node, "id", 80);
          require(ids.add(nodeId), "流程节点重复");
          choice(node, "kind", "start", "assignment", "work", "approval", "end");
          List<String> edges = new ArrayList<>();
          for (JsonNode next : array(node, "next", 0, 20)) edges.add(next.asText());
          graph.put(nodeId, edges);
        }
        for (List<String> edges : graph.values())
          for (String next : edges) require(ids.contains(next), "流程引用未知节点");
        for (String node : ids) cycle(graph, node, new HashSet<>(), new HashSet<>());
        fields(in.path("rules"), "assignment", "timeoutMinutes");
        choice(in.path("rules"), "assignment", "manual", "skill");
        integer(in.path("rules"), "timeoutMinutes", 1, 10080);
        ObjectNode definition =
            add(
                state,
                "workflows",
                o(
                    "name",
                    in.get("name"),
                    "nodes",
                    nodes,
                    "rules",
                    in.get("rules"),
                    "definitionVersion",
                    in.get("version"),
                    "status",
                    "validated",
                    "executionMode",
                    "definition_only"),
                actor,
                clock);
        return new FeatureResult(
            201,
            o(
                "definitionId",
                definition.path("id"),
                "version",
                in.get("version"),
                "validation",
                o(
                    "valid",
                    true,
                    "executionMode",
                    "definition_only",
                    "note",
                    "原有维修状态机继续执行；本地定义尚不替代在途工单流程")));
      }
      case "COST" -> {
        admin(actor);
        return FeatureResult.ok(analytics(state, in));
      }
      case "KNOWLEDGE" -> {
        String q = query.getOrDefault("q", "").trim().toLowerCase(Locale.ROOT),
            model = query.getOrDefault("deviceModel", "");
        ArrayNode items = empty(), sources = empty();
        for (JsonNode item : rows(state, "knowledge"))
          if (item.path("approved").asBoolean()
              && (model.isEmpty()
                  || model.equals(item.path("deviceModel").asText())
                  || item.path("deviceModel").asText().equals("generic"))
              && (item.path("title").asText() + item.path("content").asText())
                  .toLowerCase(Locale.ROOT)
                  .contains(q)) {
            items.add(item);
            for (JsonNode source : item.path("sourceReferences")) sources.add(source);
          }
        return FeatureResult.ok(
            o("items", items, "total", items.size(), "sourceReferences", sources));
      }
      case "INVENTORY" -> {
        admin(actor);
        return FeatureResult.ok(inventory(state, in, actor, clock));
      }
      case "PROCUREMENT" -> {
        admin(actor);
        fields(in, "supplierId", "reason", "lines", "contractId");
        String supplier = text(in, "supplierId", 80);
        contract(state, text(in, "contractId", 80), supplier, clock);
        text(in, "reason", 500);
        for (JsonNode line : array(in, "lines", 1, 50)) {
          fields(line, "partId", "quantity");
          ref(state, "parts", text(line, "partId", 80));
          number(line, "quantity", 0.001, 1000000);
        }
        ObjectNode request =
            add(
                state,
                "procurementRequests",
                o(
                    "supplierId",
                    supplier,
                    "contractId",
                    in.get("contractId"),
                    "reason",
                    in.get("reason"),
                    "lines",
                    in.get("lines"),
                    "status",
                    "pending_approval"),
                actor,
                clock);
        return new FeatureResult(
            201, o("requestId", request.path("id"), "approvalStatus", request.path("status")));
      }
      case "procurementAction" -> {
        admin(actor);
        fields(in, "version", "action", "warehouseId");
        ObjectNode request = ref(state, "procurementRequests", id);
        version(request, in);
        String action = choice(in, "action", "approve", "reject", "receive");
        if (action.equals("receive")) {
          require(request.path("status").asText().equals("approved"), "须先批准采购申请");
          ArrayNode lines = empty();
          for (JsonNode line : request.path("lines"))
            lines.add(
                o(
                    "partId",
                    line.get("partId"),
                    "quantity",
                    line.get("quantity"),
                    "unit",
                    ref(state, "parts", line.path("partId").asText()).path("unit")));
          JsonNode receipt =
              inventory(
                  state,
                  o(
                      "requestId",
                      "procurement:" + id,
                      "kind",
                      "receipt",
                      "warehouseId",
                      text(in, "warehouseId", 80),
                      "lines",
                      lines),
                  actor,
                  clock);
          request.set("receipt", receipt);
          request.put("status", "received");
        } else {
          require(request.path("status").asText().equals("pending_approval"), "采购申请已处理");
          request.put("status", action.equals("approve") ? "approved" : "rejected");
        }
        request.put("version", request.path("version").asInt() + 1);
        audit(state, actor, "procurementRequests", id, action, clock);
        return FeatureResult.ok(request);
      }
      case "OUTSOURCE" -> {
        admin(actor);
        fields(in, "orderId", "supplierId", "contractId", "expectedReturnDate");
        String order = text(in, "orderId", 80);
        core(state, "orders", order);
        String supplier = text(in, "supplierId", 80);
        contract(state, text(in, "contractId", 80), supplier, clock);
        String date = date(in, "expectedReturnDate");
        require(date.compareTo(DemoDomain.today(clock)) >= 0, "预计返还日期不能早于今天");
        for (JsonNode row : rows(state, "outsourceOrders"))
          require(
              !row.path("orderId").asText().equals(order)
                  || row.path("status").asText().equals("completed"),
              "工单已有未完成委外记录");
        ObjectNode external =
            add(
                state,
                "outsourceOrders",
                o(
                    "orderId",
                    order,
                    "supplierId",
                    supplier,
                    "contractId",
                    in.get("contractId"),
                    "expectedReturnDate",
                    date,
                    "status",
                    "registered_locally",
                    "externalDelivery",
                    false),
                actor,
                clock);
        return new FeatureResult(
            201,
            o(
                "externalOrderId",
                external.path("id"),
                "status",
                external.path("status"),
                "externalDelivery",
                false));
      }
      case "INTEGRATION" -> {
        admin(actor);
        fields(in, "system", "direction", "entity", "cursor", "requestId", "records");
        String requestId = text(in, "requestId", 100);
        for (JsonNode job : rows(state, "integrationJobs"))
          if (job.path("requestId").asText().equals(requestId)) {
            require(
                job.path("request").equals(FeatureDomain.redacted(in.deepCopy())),
                "同一 requestId 对应不同请求");
            return new FeatureResult(job.path("httpStatus").asInt(200), job.path("result"));
          }
        if (!in.path("system").asText().equals("local")
            || !in.path("direction").asText().equals("import")
            || !in.path("entity").asText().equals("equipment")) {
          FeatureResult result =
              FeatureDomain.unavailable(
                  state, "integrationJobs", text(in, "system", 80), in, actor, clock);
          ObjectNode row = (ObjectNode) rows(state, "integrationJobs").get(0);
          row.put("requestId", requestId);
          row.put("httpStatus", 503);
          row.set("result", result.data());
          return result;
        }
        ArrayNode imported = empty();
        for (JsonNode record : array(in, "records", 1, 100))
          imported.add(DemoDomain.mutate(state, "createDevice", null, record, actor, clock));
        ObjectNode job =
            add(
                state,
                "integrationJobs",
                o(
                    "requestId",
                    requestId,
                    "request",
                    in,
                    "status",
                    "completed",
                    "importedIds",
                    imported),
                actor,
                clock);
        JsonNode result =
            o(
                "jobId",
                job.path("id"),
                "accepted",
                true,
                "status",
                "completed",
                "importedCount",
                imported.size(),
                "reconciliationUrl",
                "api/records/integrationJobs");
        job.put("httpStatus", 200);
        job.set("result", result);
        return FeatureResult.ok(result);
      }
      case "REPORT" -> {
        admin(actor);
        fields(in, "reportType", "filters", "format", "templateId");
        String type = choice(in, "reportType", "devices", "orders", "tasks", "inventory");
        String format = choice(in, "format", "csv", "pdf");
        if (format.equals("pdf"))
          throw fail(422, "FORMAT_NOT_SUPPORTED", "本地生成器支持 CSV；PDF 模板/排版服务尚未配置");
        require(in.path("filters").isObject(), "filters 须为对象");
        fields(in.path("filters"), "status", "deviceId");
        ArrayNode data =
            type.equals("inventory") ? rows(state, "inventory") : (ArrayNode) state.get(type);
        String[] columns =
            type.equals("devices")
                ? new String[] {"id", "code", "name", "location", "category"}
                : type.equals("orders")
                    ? new String[] {"id", "number", "deviceId", "title", "status", "priority"}
                    : type.equals("tasks")
                        ? new String[] {
                          "id", "number", "deviceId", "name", "scheduledDate", "status"
                        }
                        : new String[] {"warehouseId", "partId", "quantity"};
        StringBuilder csv = new StringBuilder("\uFEFF");
        csv.append(String.join(",", columns)).append("\r\n");
        int count = 0;
        for (JsonNode row : data) {
          if (in.path("filters").has("status")
              && !in.path("filters").path("status").asText().equals(row.path("status").asText()))
            continue;
          if (in.path("filters").has("deviceId")
              && !in.path("filters")
                  .path("deviceId")
                  .asText()
                  .equals(row.path("deviceId").asText())) continue;
          for (int i = 0; i < columns.length; i++) {
            if (i > 0) csv.append(',');
            String value = row.path(columns[i]).asText();
            if (value.matches("^[=+@-].*")) value = "'" + value;
            csv.append('"').append(value.replace("\"", "\"\"")).append('"');
          }
          csv.append("\r\n");
          count++;
        }
        ObjectNode file =
            vault.generated(
                state,
                type + ".csv",
                "text/csv",
                csv.toString().getBytes(StandardCharsets.UTF_8),
                actor,
                clock);
        ObjectNode job =
            add(
                state,
                "reports",
                o(
                    "reportType",
                    type,
                    "filters",
                    in.path("filters"),
                    "rowCount",
                    count,
                    "fileId",
                    file.path("id"),
                    "status",
                    "completed"),
                actor,
                clock);
        return FeatureResult.ok(
            o(
                "jobId",
                job.path("id"),
                "status",
                "completed",
                "downloadUrl",
                "api/files/" + file.path("id").asText() + "/content",
                "expiresAt",
                clock.instant().plusSeconds(86400).toString()));
      }
      case "LABEL" -> {
        fields(in, "entityType", "entityIds", "format", "templateId");
        choice(in, "entityType", "device");
        String format = choice(in, "format", "qr", "nfc", "rfid");
        if (!format.equals("qr"))
          throw fail(422, "HARDWARE_NOT_CONFIGURED", "本地支持 QR SVG；NFC/RFID 写入器未配置");
        ArrayNode labels = empty();
        for (JsonNode entity : array(in, "entityIds", 1, 50)) {
          String deviceId = entity.asText();
          entityAccess(state, "device", deviceId, actor);
          ObjectNode label =
              add(state, "labels", o("deviceId", deviceId, "active", true), actor, clock);
          String token = label.path("id").asText();
          String payload = "equipment-maintenance:device:" + deviceId + ":label:" + token;
          try {
            BitMatrix matrix =
                new MultiFormatWriter()
                    .encode(
                        payload,
                        BarcodeFormat.QR_CODE,
                        256,
                        256,
                        Map.of(EncodeHintType.CHARACTER_SET, "UTF-8"));
            StringBuilder svg =
                new StringBuilder(
                    "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 256 256\"><rect"
                        + " width=\"256\" height=\"256\" fill=\"white\"/><path d=\"");
            for (int y = 0; y < 256; y++)
              for (int x = 0; x < 256; x++)
                if (matrix.get(x, y))
                  svg.append("M").append(x).append(' ').append(y).append("h1v1h-1z");
            svg.append("\" fill=\"black\"/></svg>");
            ObjectNode file =
                vault.generated(
                    state,
                    "label-" + token + ".svg",
                    "image/svg+xml",
                    svg.toString().getBytes(StandardCharsets.UTF_8),
                    actor,
                    clock);
            label.put("fileId", file.path("id").asText());
            label.put("payload", payload);
            labels.add(
                o(
                    "id",
                    token,
                    "deviceId",
                    deviceId,
                    "payload",
                    payload,
                    "downloadUrl",
                    "api/files/" + file.path("id").asText() + "/content"));
          } catch (WriterException e) {
            throw new IOException(e);
          }
        }
        return FeatureResult.ok(
            o("labels", labels, "printJobId", null, "printMode", "download_svg"));
      }
      default -> {
        return null;
      }
    }
  }

  private static long ChronoUnitDays(String from, String to) {
    return java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(from), LocalDate.parse(to));
  }

  static void cycle(
      Map<String, List<String>> graph, String node, Set<String> visiting, Set<String> visited) {
    if (visited.contains(node)) return;
    require(visiting.add(node), "关系/流程存在环路");
    for (String next : graph.getOrDefault(node, List.of())) cycle(graph, next, visiting, visited);
    visiting.remove(node);
    visited.add(node);
  }

  static ObjectNode uploaded(ObjectNode state, String id, String actor) {
    ObjectNode file = ref(state, "files", id);
    require(file.path("status").asText().equals("uploaded"), "附件尚未上传");
    if (!actor.equals("admin") && !actor.equals(file.path("createdBy").asText()))
      entityAccess(state, file.path("entityType").asText(), file.path("entityId").asText(), actor);
    return file;
  }

  private static void contract(ObjectNode state, String id, String supplier, Clock clock) {
    ref(state, "suppliers", supplier);
    ObjectNode contract = ref(state, "contracts", id);
    require(
        contract.path("supplierId").asText().equals(supplier)
            && contract.path("active").asBoolean()
            && contract.path("validUntil").asText().compareTo(DemoDomain.today(clock)) >= 0,
        "合同无效、过期或不属于此供应商");
  }

  static JsonNode inventory(ObjectNode state, JsonNode in, String actor, Clock clock) {
    fields(in, "requestId", "kind", "warehouseId", "orderId", "lines");
    String requestId = text(in, "requestId", 100);
    for (JsonNode txn : rows(state, "inventoryTransactions"))
      if (txn.path("requestId").asText().equals(requestId)) {
        require(txn.path("request").equals(in), "同一 requestId 对应不同内容");
        return txn.path("result");
      }
    String kind = choice(in, "kind", "issue", "return", "receipt"),
        warehouse = text(in, "warehouseId", 80);
    ref(state, "warehouses", warehouse);
    String order = optional(in, "orderId", 80);
    if (!kind.equals("receipt")) {
      ObjectNode work = core(state, "orders", text(in, "orderId", 80));
      if (kind.equals("issue"))
        require(work.path("status").asText().equals("in_progress"), "仅维修中的工单可以领料");
    }
    ArrayNode balances = empty(), lines = array(in, "lines", 1, 50);
    Set<String> unique = new HashSet<>();
    for (JsonNode line : lines) {
      fields(line, "partId", "quantity", "unit");
      String partId = text(line, "partId", 80);
      ObjectNode part = ref(state, "parts", partId);
      require(unique.add(partId), "备件不能重复");
      require(text(line, "unit", 20).equals(part.path("unit").asText()), "备件单位不匹配");
      number(line, "quantity", 0.001, 1000000);
      BigDecimal quantity = line.path("quantity").decimalValue();
      require(quantity.scale() <= 3, "数量最多3位小数");
      ObjectNode balance = null;
      for (JsonNode b : rows(state, "inventory"))
        if (b.path("warehouseId").asText().equals(warehouse)
            && b.path("partId").asText().equals(partId)) balance = (ObjectNode) b;
      if (balance == null) {
        balance =
            o(
                "id",
                warehouse + ":" + partId,
                "warehouseId",
                warehouse,
                "partId",
                partId,
                "quantity",
                0);
        rows(state, "inventory").add(balance);
      }
      BigDecimal current = balance.path("quantity").decimalValue();
      if (kind.equals("issue") && current.compareTo(quantity) < 0)
        throw fail(409, "INSUFFICIENT_STOCK", "库存不足：" + partId);
      if (kind.equals("return")) {
        BigDecimal net = BigDecimal.ZERO;
        for (JsonNode txn : rows(state, "inventoryTransactions"))
          if (txn.path("orderId").asText().equals(order)
              && txn.path("warehouseId").asText().equals(warehouse))
            for (JsonNode prior : txn.path("lines"))
              if (prior.path("partId").asText().equals(partId))
                net =
                    net.add(
                        prior
                            .path("quantity")
                            .decimalValue()
                            .multiply(
                                BigDecimal.valueOf(
                                    txn.path("kind").asText().equals("issue")
                                        ? 1
                                        : txn.path("kind").asText().equals("return") ? -1 : 0)));
        if (net.compareTo(quantity) < 0) throw fail(409, "EXCESS_RETURN", "退料超过该工单的净领料数量");
      }
      BigDecimal next = kind.equals("issue") ? current.subtract(quantity) : current.add(quantity);
      balance.put("quantity", next);
      balances.add(balance.deepCopy());
    }
    ObjectNode txn =
        add(
            state,
            "inventoryTransactions",
            o(
                "requestId",
                requestId,
                "kind",
                kind,
                "warehouseId",
                warehouse,
                "orderId",
                order,
                "lines",
                lines,
                "request",
                in),
            actor,
            clock);
    JsonNode result =
        o(
            "transactionId",
            txn.path("id"),
            "balances",
            balances,
            "ledgerReferences",
            List.of(txn.path("id").asText()));
    txn.set("result", result);
    return result;
  }

  private static JsonNode telemetry(ObjectNode state, JsonNode in, String actor, Clock clock) {
    admin(actor);
    fields(in, "deviceId", "timestamp", "metrics", "eventId");
    String deviceId = text(in, "deviceId", 80), eventId = text(in, "eventId", 100);
    ObjectNode device = core(state, "devices", deviceId);
    require(
        !Set.of("inactive", "scrapped").contains(device.path("lifecycleStatus").asText()), "设备已停用");
    Instant at = instant(in, "timestamp");
    require(!at.isAfter(clock.instant().plusSeconds(300)), "遥测时间超前超过5分钟");
    require(
        in.path("metrics").isObject()
            && in.path("metrics").size() > 0
            && in.path("metrics").size() <= 20,
        "metrics须为1–20项");
    for (var fields = in.path("metrics").fields(); fields.hasNext(); ) {
      var field = fields.next();
      require(
          field.getKey().matches("[A-Za-z][A-Za-z0-9_]{0,39}")
              && field.getValue().isNumber()
              && Double.isFinite(field.getValue().asDouble()),
          "点位名称/数值无效");
    }
    Instant latest = Instant.MIN;
    for (JsonNode row : rows(state, "telemetry"))
      if (row.path("deviceId").asText().equals(deviceId)) {
        if (row.path("eventId").asText().equals(eventId)) {
          require(row.path("request").equals(in), "重复事件内容不一致");
          return o(
              "accepted",
              true,
              "duplicate",
              true,
              "outOfOrder",
              row.path("outOfOrder"),
              "alarmIds",
              row.path("alarmIds"));
        }
        Instant time = Instant.parse(row.path("timestamp").asText());
        if (time.isAfter(latest)) latest = time;
      }
    boolean outOfOrder = at.isBefore(latest);
    ArrayNode alarms = empty();
    if (!outOfOrder) {
      List<String> abnormal = new ArrayList<>();
      JsonNode thresholds =
          features(state).path("settings").path("dictionaries").path("iotThresholds");
      for (String metric : List.of("temperature", "vibration"))
        if (in.path("metrics").has(metric)
            && in.path("metrics").path(metric).asDouble()
                > thresholds.path(metric).asDouble(metric.equals("temperature") ? 80 : 10))
          abnormal.add(metric);
      ObjectNode active = null;
      for (JsonNode alarm : rows(state, "alarms"))
        if (alarm.path("deviceId").asText().equals(deviceId)
            && alarm.path("status").asText().equals("active")) active = (ObjectNode) alarm;
      if (!abnormal.isEmpty()) {
        if (active == null) {
          JsonNode order =
              DemoDomain.mutate(
                  state,
                  "createOrder",
                  null,
                  o(
                      "deviceId",
                      deviceId,
                      "title",
                      "本地阈值告警",
                      "description",
                      "超阈值点位：" + String.join(", ", abnormal),
                      "priority",
                      "urgent"),
                  actor,
                  clock);
          active =
              add(
                  state,
                  "alarms",
                  o(
                      "deviceId",
                      deviceId,
                      "status",
                      "active",
                      "metricNames",
                      abnormal,
                      "orderId",
                      order.path("id")),
                  actor,
                  clock);
        }
        alarms.add(active.path("id"));
      } else if (active != null
          && in.path("metrics").has("temperature")
          && in.path("metrics").has("vibration")) {
        active.put("status", "cleared");
        active.put("clearedAt", clock.instant().toString());
      }
    }
    add(
        state,
        "telemetry",
        o(
            "deviceId",
            deviceId,
            "eventId",
            eventId,
            "timestamp",
            at.toString(),
            "metrics",
            in.get("metrics"),
            "request",
            in,
            "outOfOrder",
            outOfOrder,
            "alarmIds",
            alarms),
        actor,
        clock);
    return o("accepted", true, "duplicate", false, "outOfOrder", outOfOrder, "alarmIds", alarms);
  }

  private static JsonNode analytics(ObjectNode state, JsonNode in) {
    fields(in, "period", "metrics", "dimensions", "inputs");
    fields(in.path("period"), "from", "to");
    String from = date(in.path("period"), "from"), to = date(in.path("period"), "to");
    require(to.compareTo(from) >= 0, "统计截止日期早于起始日期");
    ArrayNode requested = array(in, "metrics", 1, 10), missing = empty(), refs = empty();
    array(in, "dimensions", 0, 10);
    ObjectNode metrics = o();
    double minutes = 0;
    int completions = 0;
    for (JsonNode order : state.path("orders"))
      for (JsonNode completion : order.path("completions")) {
        String day =
            Instant.parse(completion.path("at").asText())
                .atZone(DemoDomain.SHANGHAI)
                .toLocalDate()
                .toString();
        if (day.compareTo(from) >= 0 && day.compareTo(to) <= 0) {
          minutes += completion.path("workMinutes").asDouble();
          completions++;
          refs.add(order.path("id"));
        }
      }
    for (JsonNode name : requested)
      switch (name.asText()) {
        case "MTTR" -> {
          if (completions == 0) {
            metrics.putNull("MTTR");
            missing.add("统计期内完工记录");
          } else metrics.put("MTTR", minutes / completions);
        }
        case "MTBF" -> {
          if (!in.path("inputs").has("runtimeMinutes") || completions == 0) {
            metrics.putNull("MTBF");
            missing.add("runtimeMinutes 与统计期故障次数");
          } else
            metrics.put("MTBF", number(in.path("inputs"), "runtimeMinutes", 0, 1e12) / completions);
        }
        case "OEE" -> {
          JsonNode values = in.path("inputs");
          if (!values.has("plannedMinutes")
              || !values.has("runningMinutes")
              || !values.has("totalCount")
              || !values.has("goodCount")
              || !values.has("idealCycleMinutes")) {
            metrics.putNull("OEE");
            missing.add("plannedMinutes/runningMinutes/totalCount/goodCount/idealCycleMinutes");
          } else {
            double planned = number(values, "plannedMinutes", 0.001, 1e12),
                running = number(values, "runningMinutes", 0.001, planned),
                total = number(values, "totalCount", 1, 1e12),
                good = number(values, "goodCount", 0, total),
                ideal = number(values, "idealCycleMinutes", 0.0001, 1e6);
            double performance = ideal * total / running;
            require(performance <= 1, "理论产出时长大于运行时长，请核对单位");
            metrics.put("OEE", running / planned * performance * good / total);
          }
        }
        default -> throw fail(400, "VALIDATION_ERROR", "未知指标：" + name.asText());
      }
    return o(
        "metrics",
        metrics,
        "missingInputs",
        missing,
        "sourceReferences",
        refs,
        "unit",
        o("MTTR", "minute", "MTBF", "minute", "OEE", "ratio"),
        "scope",
        "MTTR/MTBF本地以完工尝试为单位；MTBF需外部运行时长输入，尚不代表正式财务或绩效口径",
        "dimensionsApplied",
        false);
  }
}
