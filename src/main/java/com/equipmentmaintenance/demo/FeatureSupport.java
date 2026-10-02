package com.equipmentmaintenance.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.*;
import java.util.*;

/** Validation and record helpers shared by the local feature modules. */
final class FeatureSupport {
  static ObjectNode o(Object... pairs) {
    return DemoDomain.object(pairs);
  }

  static ObjectNode features(ObjectNode state) {
    return (ObjectNode) state.get("features");
  }

  static ArrayNode rows(ObjectNode state, String kind) {
    JsonNode value = features(state).get(kind);
    if (!(value instanceof ArrayNode a)) throw fail(404, "NOT_FOUND", "未知集合：" + kind);
    return a;
  }

  static DemoException fail(int status, String code, String message) {
    return new DemoException(status, code, message);
  }

  static void require(boolean condition, String message) {
    if (!condition) throw fail(400, "VALIDATION_ERROR", message);
  }

  static String text(JsonNode input, String key, int max) {
    JsonNode value = input.get(key);
    require(
        value != null
            && value.isTextual()
            && !value.asText().trim().isEmpty()
            && value.asText().trim().length() <= max,
        key + "须为 1–" + max + " 字符");
    return value.asText().trim();
  }

  static String optional(JsonNode input, String key, int max) {
    if (!input.hasNonNull(key)) return "";
    require(
        input.get(key).isTextual() && input.get(key).asText().trim().length() <= max, key + "无效");
    return input.get(key).asText().trim();
  }

  static double number(JsonNode input, String key, double min, double max) {
    JsonNode value = input.get(key);
    require(
        value != null
            && value.isNumber()
            && Double.isFinite(value.asDouble())
            && value.asDouble() >= min
            && value.asDouble() <= max,
        key + "须在 " + min + "–" + max + " 之间");
    return value.asDouble();
  }

  static int integer(JsonNode input, String key, int min, int max) {
    require(input.path(key).isIntegralNumber() && input.path(key).canConvertToInt(), key + "须为整数");
    return (int) number(input, key, min, max);
  }

  static String choice(JsonNode input, String key, String... values) {
    String value = text(input, key, 100);
    require(Arrays.asList(values).contains(value), key + "支持：" + String.join(", ", values));
    return value;
  }

  static ArrayNode array(JsonNode input, String key, int min, int max) {
    require(
        input.path(key).isArray() && input.path(key).size() >= min && input.path(key).size() <= max,
        key + "须含 " + min + "–" + max + " 项");
    return (ArrayNode) input.get(key);
  }

  static void fields(JsonNode input, String... names) {
    require(input.isObject(), "请求须为 JSON 对象");
    Set<String> allowed = new HashSet<>(Arrays.asList(names));
    input.fieldNames().forEachRemaining(k -> require(allowed.contains(k), "不支持的字段：" + k));
  }

  static String date(JsonNode input, String key) {
    String value = text(input, key, 10);
    try {
      LocalDate parsed = LocalDate.parse(value);
      require(
          value.matches("\\d{4}-\\d{2}-\\d{2}")
              && parsed.getYear() >= 2000
              && parsed.getYear() <= 2100,
          key + "日期范围为2000–2100");
    } catch (DateTimeException e) {
      throw fail(400, "VALIDATION_ERROR", key + "不是有效 YYYY-MM-DD 日期");
    }
    return value;
  }

  static Instant instant(JsonNode input, String key) {
    try {
      return Instant.parse(text(input, key, 40));
    } catch (DateTimeException e) {
      throw fail(400, "VALIDATION_ERROR", key + "不是有效 ISO 时间");
    }
  }

  static ObjectNode find(ArrayNode list, String id) {
    for (JsonNode item : list) if (item.path("id").asText().equals(id)) return (ObjectNode) item;
    throw fail(404, "NOT_FOUND", "记录不存在：" + id);
  }

  static ObjectNode ref(ObjectNode state, String kind, String id) {
    return find(rows(state, kind), id);
  }

  static ObjectNode core(ObjectNode state, String kind, String id) {
    return find((ArrayNode) state.get(kind), id);
  }

  static void version(JsonNode entity, JsonNode input) {
    if (integer(input, "version", 1, Integer.MAX_VALUE) != entity.path("version").asInt())
      throw fail(409, "VERSION_CONFLICT", "记录已更新，请重新读取");
  }

  static void admin(String actor) {
    if (!"admin".equals(actor)) throw fail(403, "FORBIDDEN", "需要管理员权限");
  }

  static void entityAccess(ObjectNode state, String type, String id, String actor) {
    String collection =
        switch (type) {
          case "device" -> "devices";
          case "order" -> "orders";
          case "task" -> "tasks";
          default -> throw fail(400, "VALIDATION_ERROR", "entityType 支持 device/order/task");
        };
    ObjectNode record = core(state, collection, id);
    if ("admin".equals(actor)
        || actor.equals(record.path("reporterId").asText())
        || actor.equals(record.path("assigneeId").asText())) return;
    if ("device".equals(type)) {
      String department = record.path("departmentId").asText("设备部");
      for (JsonNode permitted : ref(state, "roles", actor).path("dataScope").path("departmentIds"))
        if (department.equals(permitted.asText())) return;
    }
    throw fail(403, "FORBIDDEN", "无权操作该业务记录");
  }

  static ObjectNode add(
      ObjectNode state, String kind, ObjectNode payload, String actor, Clock clock) {
    ObjectNode result = payload.deepCopy();
    result.put("id", UUID.randomUUID().toString());
    result.put("createdBy", actor);
    result.put("createdAt", clock.instant().toString());
    result.put("version", 1);
    rows(state, kind).insert(0, result);
    audit(state, actor, kind, result.path("id").asText(), "created", clock);
    return result;
  }

  static void audit(
      ObjectNode state, String actor, String type, String id, String detail, Clock clock) {
    ((ArrayNode) state.get("audit"))
        .insert(
            0,
            o(
                "id",
                UUID.randomUUID().toString(),
                "actorId",
                actor,
                "actorName",
                DemoDomain.actor(actor).path("name").asText(),
                "entityType",
                type,
                "entityId",
                id,
                "action",
                detail,
                "detail",
                type + " · " + detail,
                "at",
                clock.instant().toString()));
  }

  static void message(
      ObjectNode state,
      String actor,
      String recipient,
      String title,
      String type,
      String id,
      Clock clock) {
    DemoDomain.actor(recipient);
    ((ArrayNode) state.get("messages"))
        .insert(
            0,
            o(
                "id",
                UUID.randomUUID().toString(),
                "recipient",
                recipient,
                "title",
                title,
                "entityType",
                type,
                "entityId",
                id,
                "createdAt",
                clock.instant().toString(),
                "read",
                false));
  }

  static ArrayNode empty() {
    return DemoDomain.JSON.createArrayNode();
  }
}
