package com.equipmentmaintenance.demo;

import static com.equipmentmaintenance.demo.FeatureSupport.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.io.IOException;
import java.nio.file.Path;
import java.time.*;
import java.util.*;

/**
 * Routes the formerly reserved contracts to local business modules. No external success is
 * fabricated.
 */
final class FeatureDomain {
  static final Set<String> CODES =
      Set.of(
          "AUTH",
          "ACCESS",
          "SETTINGS",
          "ASSET",
          "COMPLIANCE",
          "BOM",
          "IOT",
          "SCHEDULE",
          "WORKFLOW",
          "COST",
          "KNOWLEDGE",
          "INVENTORY",
          "PROCUREMENT",
          "REPORT",
          "INTEGRATION",
          "OUTSOURCE",
          "FILE",
          "LABEL",
          "MOBILE",
          "OFFLINE",
          "SOP",
          "SIGN",
          "LOCATION",
          "OCR",
          "TOOL",
          "COLLAB",
          "AR3D",
          "TRAINING",
          "AI",
          "LOWCODE",
          "SECURITY",
          "BACKUP",
          "PUSH");
  static final Set<String> CATALOG =
      Set.of(
          "warehouses",
          "parts",
          "suppliers",
          "contracts",
          "standards",
          "certificates",
          "knowledge",
          "tools",
          "courses",
          "models",
          "sopTemplates");
  static final String[] COLLECTIONS = {
    "accounts",
    "sessions",
    "roles",
    "warehouses",
    "parts",
    "inventory",
    "suppliers",
    "contracts",
    "standards",
    "certificates",
    "knowledge",
    "tools",
    "courses",
    "models",
    "sopTemplates",
    "lifecycleRequests",
    "boms",
    "telemetry",
    "alarms",
    "workflows",
    "inventoryTransactions",
    "procurementRequests",
    "integrationJobs",
    "outsourceOrders",
    "files",
    "labels",
    "syncOperations",
    "sopExecutions",
    "signatures",
    "checkins",
    "ocrReadings",
    "toolLoans",
    "rooms",
    "roomMessages",
    "enrollments",
    "intelligenceJobs",
    "customizations",
    "auditExports",
    "backups",
    "deliveries",
    "reports",
    "complianceInspections"
  };

  static boolean initialize(ObjectNode state, Path file, Clock clock) throws IOException {
    boolean created = !state.has("features");
    if (created)
      state.set(
          "features",
          o(
              "schemaVersion",
              1,
              "serverVersion",
              1,
              "settings",
              o(
                  "version",
                  1,
                  "dictionaries",
                  o(),
                  "messageTemplates",
                  empty(),
                  "mobileForms",
                  empty())));
    ObjectNode f = features(state);
    for (String kind : COLLECTIONS) if (!f.has(kind)) f.set(kind, empty());
    FeatureAuth.initialize(state, file, clock);
    if (created) {
      String today = DemoDomain.today(clock),
          until = LocalDate.parse(today).plusDays(365).toString();
      String device = state.path("devices").get(0).path("id").asText();
      rows(state, "warehouses").add(o("id", "warehouse-main", "name", "本地演示主库", "version", 1));
      rows(state, "parts")
          .add(
              o(
                  "id",
                  "part-bearing",
                  "code",
                  "P-001",
                  "name",
                  "演示轴承",
                  "unit",
                  "piece",
                  "version",
                  1));
      rows(state, "inventory")
          .add(
              o(
                  "id",
                  "warehouse-main:part-bearing",
                  "warehouseId",
                  "warehouse-main",
                  "partId",
                  "part-bearing",
                  "quantity",
                  10.0));
      rows(state, "suppliers").add(o("id", "supplier-demo", "name", "本地虚构供应商", "version", 1));
      rows(state, "contracts")
          .add(
              o(
                  "id",
                  "contract-demo",
                  "supplierId",
                  "supplier-demo",
                  "active",
                  true,
                  "validUntil",
                  until,
                  "version",
                  1));
      rows(state, "standards")
          .add(o("id", "standard-demo", "name", "本地演示检查标准", "version", 1, "approved", true));
      rows(state, "certificates")
          .add(
              o(
                  "id",
                  "certificate-demo",
                  "deviceId",
                  device,
                  "standardVersion",
                  "standard-demo",
                  "validUntil",
                  until,
                  "status",
                  "valid",
                  "version",
                  1));
      rows(state, "knowledge")
          .add(
              o(
                  "id",
                  "knowledge-demo",
                  "title",
                  "设备异响记录方法",
                  "content",
                  "记录发生时间、工况和声音，交由获授权的维修人员评估。",
                  "deviceModel",
                  "generic",
                  "approved",
                  true,
                  "sourceReferences",
                  List.of("本地演示知识条目"),
                  "version",
                  1));
      rows(state, "tools")
          .add(
              o(
                  "id",
                  "tool-meter",
                  "name",
                  "演示测量工具",
                  "calibrationValidUntil",
                  until,
                  "status",
                  "available",
                  "version",
                  1));
      rows(state, "courses")
          .add(
              o(
                  "id",
                  "course-demo",
                  "name",
                  "演示数据记录培训",
                  "passingScore",
                  100,
                  "validDays",
                  365,
                  "questions",
                  List.of(o("id", "q1", "prompt", "发现异常是否应记录并报告？", "answer", "yes")),
                  "version",
                  1));
      rows(state, "sopTemplates")
          .add(
              o(
                  "id",
                  "sop-demo",
                  "name",
                  "演示作业记录模板",
                  "version",
                  "1",
                  "steps",
                  List.of(
                      o("id", "record", "label", "记录检查对象", "evidenceRequired", false),
                      o("id", "confirm", "label", "确认记录完整", "evidenceRequired", false))));
    }
    return created;
  }

  static FeatureResult execute(
      ObjectNode state,
      String code,
      String id,
      JsonNode input,
      String actor,
      Map<String, String> query,
      Clock clock,
      FileVault vault)
      throws IOException {
    require(input != null && input.isObject(), "请求须为 JSON 对象");
    if (code.equals("AUTH")) return FeatureResult.ok(FeatureAuth.login(state, input, clock));
    if (CODES.contains(code)) FeatureAuth.authorize(state, actor, code);
    String permission =
        switch (code) {
          case "lifecycleDecision", "devicePosition" -> "ASSET";
          case "procurementAction" -> "PROCUREMENT";
          case "ocrConfirm" -> "OCR";
          case "toolReturn" -> "TOOL";
          case "roomMessage", "roomMessages" -> "COLLAB";
          case "trainingComplete" -> "TRAINING";
          default -> null;
        };
    if (permission != null) FeatureAuth.authorize(state, actor, permission);
    FeatureResult result;
    switch (code) {
      case "password" -> {
        return FeatureResult.ok(FeatureAuth.changePassword(state, input, actor, clock));
      }
      case "logout" -> {
        fields(input);
        FeatureAuth.revoke(state, actor);
        return FeatureResult.ok(o("sessionsRevoked", true));
      }
      case "labelRevoke" -> {
        FeatureAuth.authorize(state, actor, "LABEL");
        fields(input);
        ObjectNode label = ref(state, "labels", id);
        entityAccess(state, "device", label.path("deviceId").asText(), actor);
        label.put("active", false);
        audit(state, actor, "labels", id, "revoked", clock);
        return FeatureResult.ok(label);
      }
      case "catalog" -> {
        return FeatureResult.ok(catalog(state, actor));
      }
      case "catalogCreate" -> {
        return new FeatureResult(201, createCatalog(state, id, input, actor, clock));
      }
      case "records" -> {
        return FeatureResult.ok(records(state, id, actor));
      }
      case "account" -> {
        return FeatureResult.ok(FeatureAuth.changeAccount(state, id, input, actor, clock));
      }
      case "ACCESS" -> {
        admin(actor);
        fields(input, "version", "name", "menus", "operations", "dataScope", "fields");
        ObjectNode role = ref(state, "roles", id);
        version(role, input);
        ArrayNode operations = array(input, "operations", 0, 40);
        for (JsonNode operation : operations)
          require(
              operation.isTextual()
                  && (CODES.contains(operation.asText()) || operation.asText().equals("*")),
              "不支持的操作权限");
        require(!id.equals("admin") || operations.toString().contains("\"*\""), "管理员须保留管理权限");
        array(input, "menus", 0, 30);
        array(input, "fields", 0, 50);
        fields(input.path("dataScope"), "departmentIds");
        ArrayNode departments = array(input.path("dataScope"), "departmentIds", 0, 20);
        for (JsonNode department : departments)
          require(Set.of("设备部", "生产一部").contains(department.asText()), "未知部门");
        role.put("name", text(input, "name", 80));
        role.set("operations", operations.deepCopy());
        role.set("menus", input.get("menus").deepCopy());
        role.set("fields", input.get("fields").deepCopy());
        role.set("dataScope", input.get("dataScope").deepCopy());
        role.put("version", role.path("version").asInt() + 1);
        audit(state, actor, "roles", id, "updated", clock);
        result = FeatureResult.ok(o("role", role, "version", role.path("version")));
      }
      case "SETTINGS" -> {
        admin(actor);
        fields(input, "version", "dictionaries", "messageTemplates", "mobileForms");
        ObjectNode settings = (ObjectNode) features(state).get("settings");
        version(settings, input);
        require(input.path("dictionaries").isObject(), "dictionaries 须为对象");
        array(input, "messageTemplates", 0, 30);
        validateForms(input.path("mobileForms"));
        settings.set("dictionaries", input.get("dictionaries").deepCopy());
        settings.set("messageTemplates", input.get("messageTemplates").deepCopy());
        settings.set("mobileForms", input.get("mobileForms").deepCopy());
        settings.put("version", settings.path("version").asInt() + 1);
        audit(state, actor, "settings", "settings", "updated", clock);
        result = FeatureResult.ok(o("settings", settings, "version", settings.path("version")));
      }
      case "FILE" -> {
        fields(input, "entityType", "entityId", "filename", "contentType", "size");
        String type = choice(input, "entityType", "device", "order", "task"),
            entity = text(input, "entityId", 80);
        entityAccess(state, type, entity, actor);
        String filename = text(input, "filename", 100);
        FileVault.filename(filename);
        String contentType =
            choice(
                input,
                "contentType",
                "image/png",
                "image/jpeg",
                "text/plain",
                "text/csv",
                "application/json",
                "model/gltf+json");
        int size = integer(input, "size", 1, FileVault.LIMIT);
        ObjectNode file =
            add(
                state,
                "files",
                o(
                    "entityType",
                    type,
                    "entityId",
                    entity,
                    "filename",
                    filename,
                    "contentType",
                    contentType,
                    "size",
                    size,
                    "status",
                    "reserved",
                    "expiresAt",
                    clock.instant().plusSeconds(3600).toString()),
                actor,
                clock);
        result =
            new FeatureResult(
                201,
                o(
                    "uploadId",
                    file.path("id"),
                    "uploadUrl",
                    "api/files/" + file.path("id").asText() + "/content",
                    "expiresAt",
                    file.path("expiresAt")));
      }
      case "BACKUP" -> {
        throw new IllegalStateException("BACKUP is handled atomically by DemoStore");
      }
      default -> {
        result = FeatureBusiness.execute(state, code, id, input, actor, query, clock, vault);
        if (result == null)
          result = FeatureWork.execute(state, code, id, input, actor, query, clock, vault);
        if (result == null) throw fail(404, "NOT_FOUND", "未知功能：" + code);
      }
    }
    features(state).put("serverVersion", features(state).path("serverVersion").asInt() + 1);
    return result;
  }

  static JsonNode catalog(ObjectNode state, String actor) {
    ObjectNode result =
        o("actors", DemoDomain.actors(), "settings", features(state).get("settings"));
    for (String kind : CATALOG) {
      ArrayNode copy = rows(state, kind).deepCopy();
      if (!actor.equals("admin") && kind.equals("knowledge"))
        for (int i = copy.size() - 1; i >= 0; i--)
          if (!copy.get(i).path("approved").asBoolean()) copy.remove(i);
      if (!actor.equals("admin") && kind.equals("courses"))
        for (JsonNode course : copy)
          for (JsonNode question : course.path("questions"))
            ((ObjectNode) question).remove("answer");
      result.set(kind, copy);
    }
    if (actor.equals("admin")) result.set("roles", rows(state, "roles").deepCopy());
    return result;
  }

  static JsonNode records(ObjectNode state, String kind, String actor) {
    if (kind.equals("accounts") || kind.equals("sessions"))
      throw fail(403, "FORBIDDEN", "不能查询凭据集合");
    ArrayNode result = empty();
    for (JsonNode row : rows(state, kind)) {
      boolean visible =
          actor.equals("admin")
              || actor.equals(row.path("createdBy").asText())
              || actor.equals(row.path("employeeId").asText())
              || actor.equals(row.path("borrowerId").asText());
      if (kind.equals("rooms"))
        for (JsonNode p : row.path("participantIds")) if (actor.equals(p.asText())) visible = true;
      if (visible) result.add(row);
    }
    return result;
  }

  static JsonNode createCatalog(
      ObjectNode state, String kind, JsonNode input, String actor, Clock clock) {
    admin(actor);
    require(CATALOG.contains(kind), "不支持的主数据类型");
    Map<String, String[]> schemas =
        Map.ofEntries(
            Map.entry("warehouses", new String[] {"name"}),
            Map.entry("parts", new String[] {"code", "name", "unit"}),
            Map.entry("suppliers", new String[] {"name"}),
            Map.entry("contracts", new String[] {"supplierId", "active", "validUntil"}),
            Map.entry("standards", new String[] {"name", "approved"}),
            Map.entry(
                "certificates",
                new String[] {"deviceId", "standardVersion", "validUntil", "status"}),
            Map.entry(
                "knowledge",
                new String[] {"title", "content", "deviceModel", "approved", "sourceReferences"}),
            Map.entry("tools", new String[] {"name", "calibrationValidUntil"}),
            Map.entry("courses", new String[] {"name", "passingScore", "validDays", "questions"}),
            Map.entry(
                "models", new String[] {"deviceId", "fileId", "name", "parts", "instructions"}),
            Map.entry("sopTemplates", new String[] {"name", "steps"}));
    fields(input, schemas.get(kind));
    for (String key : schemas.get(kind)) require(input.has(key), "缺少字段：" + key);
    for (String key : List.of("active", "approved"))
      if (input.has(key)) require(input.get(key).isBoolean(), key + "须为布尔值");
    for (String key : List.of("name", "code", "unit", "title", "content", "deviceModel"))
      if (input.has(key)) text(input, key, key.equals("content") ? 10000 : 200);
    for (var fields = input.fields(); fields.hasNext(); ) {
      var entry = fields.next();
      if (entry.getValue().isTextual())
        require(
            !entry.getValue().asText().isBlank() && entry.getValue().asText().length() <= 10000,
            "主数据文本为空或过长");
    }
    if (input.has("deviceId")) core(state, "devices", text(input, "deviceId", 80));
    if (input.has("supplierId")) ref(state, "suppliers", text(input, "supplierId", 80));
    for (String key : List.of("validUntil", "calibrationValidUntil"))
      if (input.has(key)) date(input, key);
    if (kind.equals("parts")) {
      text(input, "unit", 20);
      for (JsonNode p : rows(state, kind))
        if (p.path("code").asText().equalsIgnoreCase(text(input, "code", 40)))
          throw fail(409, "DUPLICATE_CODE", "备件编码重复");
    }
    if (kind.equals("tools")) {
      ObjectNode p = (ObjectNode) input.deepCopy();
      p.put("status", "available");
      return add(state, kind, p, actor, clock);
    }
    if (kind.equals("courses")) {
      integer(input, "passingScore", 1, 100);
      integer(input, "validDays", 1, 3650);
      for (JsonNode q : array(input, "questions", 1, 50)) {
        fields(q, "id", "prompt", "answer");
        text(q, "id", 80);
        text(q, "prompt", 500);
        text(q, "answer", 100);
      }
    }
    if (kind.equals("knowledge")) {
      require(input.path("approved").isBoolean(), "approved 须为布尔值");
      array(input, "sourceReferences", 1, 20);
    }
    if (kind.equals("models")) {
      ObjectNode file = ref(state, "files", text(input, "fileId", 80));
      require(
          file.path("status").asText().equals("uploaded")
              && file.path("contentType").asText().equals("model/gltf+json"),
          "模型须为已上传 glTF 2.0");
      array(input, "parts", 0, 100);
      array(input, "instructions", 0, 100);
    }
    if (kind.equals("sopTemplates")) {
      Set<String> ids = new HashSet<>();
      for (JsonNode step : array(input, "steps", 1, 30)) {
        fields(step, "id", "label", "evidenceRequired");
        require(ids.add(text(step, "id", 80)), "步骤 ID 重复");
        text(step, "label", 200);
        require(step.path("evidenceRequired").isBoolean(), "证据要求须为布尔值");
      }
    }
    return add(state, kind, (ObjectNode) input, actor, clock);
  }

  static void validateForms(JsonNode forms) {
    require(forms.isArray() && forms.size() <= 30, "forms 须为至多30项的数组");
    for (JsonNode form : forms) {
      fields(form, "id", "fields");
      text(form, "id", 80);
      Set<String> ids = new HashSet<>();
      for (JsonNode field : array(form, "fields", 1, 50)) {
        fields(field, "id", "label", "type", "required");
        require(ids.add(text(field, "id", 80)), "表单字段重复");
        text(field, "label", 100);
        choice(field, "type", "text", "number", "date", "boolean");
        require(field.path("required").isBoolean(), "required 须为布尔值");
      }
    }
  }

  static FeatureResult unavailable(
      ObjectNode state,
      String collection,
      String provider,
      JsonNode input,
      String actor,
      Clock clock) {
    ObjectNode job =
        add(
            state,
            collection,
            o(
                "provider",
                provider,
                "status",
                "blocked_configuration",
                "accepted",
                false,
                "request",
                redacted(input.deepCopy()),
                "message",
                "未配置外部服务；没有执行外部调用"),
            actor,
            clock);
    return new FeatureResult(
        503,
        o(
            "jobId",
            job.path("id"),
            "status",
            "blocked_configuration",
            "accepted",
            false,
            "code",
            "PROVIDER_NOT_CONFIGURED",
            "message",
            job.path("message")));
  }

  static JsonNode redacted(JsonNode value) {
    if (value.isObject()) {
      List<String> names = new ArrayList<>();
      value.fieldNames().forEachRemaining(names::add);
      for (String name : names) {
        String normalized = name.toLowerCase(Locale.ROOT);
        if (Set.of("authorizationcode", "credential", "password", "token", "secret", "apikey")
            .contains(normalized)) {
          ((ObjectNode) value).put(name, "[redacted]");
        } else redacted(value.get(name));
      }
    } else if (value.isArray()) for (JsonNode item : value) redacted(item);
    return value;
  }

  static void checkSop(ObjectNode state, String type, String id) {
    if (!state.has("features")) return;
    for (JsonNode execution : rows(state, "sopExecutions"))
      if (type.equals(execution.path("entityType").asText())
          && id.equals(execution.path("entityId").asText())
          && !execution.path("status").asText().equals("completed"))
        throw fail(409, "SOP_INCOMPLETE", "关联的必需 SOP 记录尚未完成");
  }
}
