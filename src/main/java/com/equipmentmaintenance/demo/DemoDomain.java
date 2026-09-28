package com.equipmentmaintenance.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class DemoDomain {
    static final ObjectMapper JSON = new ObjectMapper();
    static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    static final List<String> ORDER_STATES = List.of("pending_assignment", "pending_acceptance", "in_progress", "pending_review", "closed", "cancelled");
    private static final Map<String, String[]> ACTORS = Map.of(
            "admin", new String[]{"陈管理员", "admin", "设备部"},
            "operator", new String[]{"周操作工", "operator", "生产一部"},
            "technician", new String[]{"顾维修工", "technician", "设备部"},
            "inspector", new String[]{"林巡检员", "inspector", "设备部"}
    );

    private DemoDomain() {}

    static ObjectNode object(Object... pairs) {
        ObjectNode result = JSON.createObjectNode();
        for (int index = 0; index < pairs.length; index += 2) {
            Object value = pairs[index + 1];
            result.set((String) pairs[index], value instanceof JsonNode node ? node : JSON.valueToTree(value));
        }
        return result;
    }

    static ArrayNode actors() {
        ArrayNode result = JSON.createArrayNode();
        for (String id : List.of("admin", "operator", "technician", "inspector")) {
            String[] entry = ACTORS.get(id);
            result.add(object("id", id, "name", entry[0], "role", entry[1], "department", entry[2]));
        }
        return result;
    }

    static ObjectNode actor(String id) {
        String[] entry = ACTORS.get(id);
        if (entry == null) throw new DemoException(401, "DEMO_ACTOR_REQUIRED", "请通过 X-Demo-Actor 提供有效演示角色");
        return object("id", id, "name", entry[0], "role", entry[1], "department", entry[2]);
    }

    static String today(Clock clock) { return LocalDate.now(clock.withZone(SHANGHAI)).toString(); }

    private static DemoException bad(String message) { return new DemoException(400, "VALIDATION_ERROR", message); }

    private static void fields(JsonNode value, String... allowed) {
        if (!(value instanceof ObjectNode)) throw bad("请求体必须为 JSON 对象");
        Set<String> names = Set.of(allowed);
        value.fieldNames().forEachRemaining(name -> {
            if (!names.contains(name)) throw bad("不支持的字段：" + name);
        });
    }

    private static String text(JsonNode value, String key, String label, int max, boolean optional) {
        JsonNode node = value.get(key);
        if (optional && (node == null || node.isNull() || node.isTextual() && node.textValue().isEmpty())) return "";
        if (node == null || !node.isTextual() || node.textValue().trim().isEmpty() || node.textValue().trim().length() > max) {
            throw bad(label + "须为 1–" + max + " 字符");
        }
        return node.textValue().trim();
    }

    private static int integer(JsonNode value, String key, String label, int min, int max) {
        JsonNode node = value.get(key);
        if (node == null || !node.isIntegralNumber() || !node.canConvertToInt() || node.intValue() < min || node.intValue() > max) {
            throw bad(label + "须为 " + min + "–" + max + " 的整数");
        }
        return node.intValue();
    }

    private static String choice(JsonNode value, String key, String label, String... options) {
        JsonNode node = value.get(key);
        if (node == null || !node.isTextual() || !Arrays.asList(options).contains(node.textValue())) {
            throw bad(label + "须为 " + String.join(" / ", options));
        }
        return node.textValue();
    }

    private static String date(JsonNode value, String key, String label) {
        String supplied = text(value, key, label, 10, false);
        if (!supplied.matches("\\d{4}-\\d{2}-\\d{2}")) throw bad(label + "必须为 YYYY-MM-DD");
        try {
            LocalDate parsed = LocalDate.parse(supplied);
            if (parsed.isBefore(LocalDate.of(2000, 1, 1)) || parsed.isAfter(LocalDate.of(2100, 12, 31))) throw bad(label + "不是有效日期（2000–2100）");
        } catch (DateTimeParseException error) {
            throw bad(label + "不是有效日期（2000–2100）");
        }
        return supplied;
    }

    private static ObjectNode find(ArrayNode list, String id, String label) {
        for (JsonNode entry : list) if (id != null && id.equals(entry.path("id").asText())) return (ObjectNode) entry;
        throw new DemoException(404, "NOT_FOUND", label + "不存在");
    }

    private static void version(ObjectNode entry, JsonNode input) {
        int expected = integer(input, "version", "version", 1, Integer.MAX_VALUE);
        if (entry.path("version").asInt() != expected) {
            throw new DemoException(409, "VERSION_CONFLICT", "记录已更新，请刷新后重试",
                    object("currentVersion", entry.path("version").asInt()));
        }
    }

    private static void requireStatus(ObjectNode entry, String... allowed) {
        String actual = entry.path("status").asText();
        if (!Arrays.asList(allowed).contains(actual)) {
            throw new DemoException(409, "INVALID_STATE", "当前状态不允许该操作",
                    object("status", actual, "allowed", allowed));
        }
    }

    private static void admin(ObjectNode actor) {
        if (!"admin".equals(actor.path("role").asText())) throw new DemoException(403, "FORBIDDEN", "当前演示角色没有管理权限");
    }

    private static void executor(ObjectNode actor, String assigneeId) {
        if (!"admin".equals(actor.path("role").asText()) && !actor.path("id").asText().equals(assigneeId)) {
            throw new DemoException(403, "FORBIDDEN", "只有指派的执行人或管理员可执行");
        }
    }

    private static ArrayNode array(ObjectNode parent, String key) { return (ArrayNode) parent.get(key); }
    private static String uuid() { return UUID.randomUUID().toString(); }

    private static void event(ObjectNode store, ObjectNode actor, String entityType, ObjectNode entity,
                              String action, String detail, String at, String... recipients) {
        ObjectNode event = object("id", uuid(), "actorId", actor.path("id").asText(), "actorName", actor.path("name").asText(),
                "entityType", entityType, "entityId", entity.path("id").asText(), "action", action, "detail", detail, "at", at);
        array(store, "audit").insert(0, event);
        if (entity.has("history")) array(entity, "history").add(event);
        for (String recipient : new LinkedHashSet<>(Arrays.asList(recipients))) {
            if (recipient != null && !recipient.isBlank()) {
                array(store, "messages").insert(0, object("id", uuid(), "recipient", recipient, "title", detail,
                        "entityType", entityType, "entityId", entity.path("id").asText(), "createdAt", at, "read", false));
            }
        }
    }

    private static ObjectNode newOrder(ObjectNode store, String deviceId, String title, String description,
                                       String priority, ObjectNode actor, String at, String sourceTaskId) {
        ObjectNode counters = (ObjectNode) store.get("counters");
        int number = counters.path("order").asInt() + 1;
        counters.put("order", number);
        String id = uuid();
        ObjectNode order = object("id", id, "number", "WO-" + String.format("%04d", number),
                "deviceId", deviceId, "title", title, "description", description, "priority", priority,
                "reporterId", actor.path("id").asText(), "assigneeId", null, "status", "pending_assignment",
                "sourceTaskId", sourceTaskId, "createdAt", at, "updatedAt", at, "closedAt", null,
                "version", 1, "completions", JSON.createArrayNode(), "reviews", JSON.createArrayNode(), "history", JSON.createArrayNode());
        array(store, "orders").insert(0, order);
        event(store, actor, "order", order, "created", order.path("number").asText() + "：" + title, at, "admin");
        return order;
    }

    static ObjectNode seed(Clock clock) {
        String day = today(clock);
        ObjectNode store = object("schemaVersion", 1, "counters", object("order", 0, "task", 0),
                "devices", JSON.createArrayNode(), "orders", JSON.createArrayNode(), "plans", JSON.createArrayNode(),
                "tasks", JSON.createArrayNode(), "messages", JSON.createArrayNode(), "audit", JSON.createArrayNode());
        mutate(store, "createDevice", null, object("code", "EQ-001", "name", "数控加工中心", "location", "一号车间 · 机加工线", "category", "加工设备"), "admin", clock);
        mutate(store, "createDevice", null, object("code", "EQ-002", "name", "空压机", "location", "动力站 · A 区", "category", "动力设备"), "admin", clock);
        mutate(store, "createDevice", null, object("code", "EQ-003", "name", "输送机", "location", "二号车间 · 装配线", "category", "输送设备"), "admin", clock);
        // Look up example codes explicitly; generated record IDs are UUIDs.
        return seedExamples(store, day, clock);
    }

    private static ObjectNode seedExamples(ObjectNode store, String day, Clock clock) {
        String machine = deviceByCode(store, "EQ-001").path("id").asText();
        String compressor = deviceByCode(store, "EQ-002").path("id").asText();
        mutate(store, "createOrder", null, object("deviceId", machine, "title", "主轴运行时出现异响",
                "description", "演示故障：加工过程中主轴存在间歇性异响，请检查。", "priority", "urgent"), "operator", clock);
        mutate(store, "createPlan", null, object("deviceId", machine, "name", "加工中心每日点检", "type", "inspection",
                "intervalDays", 1, "nextDueDate", LocalDate.parse(day).minusDays(1).toString(), "assigneeId", "inspector",
                "checklist", List.of("润滑油位", "主轴声音", "防护门状态")), "admin", clock);
        mutate(store, "createPlan", null, object("deviceId", compressor, "name", "空压机周期保养", "type", "maintenance",
                "intervalDays", 7, "nextDueDate", day, "assigneeId", "technician",
                "checklist", List.of("滤芯检查", "连接件检查")), "admin", clock);
        mutate(store, "generateTasks", null, object("throughDate", day), "admin", clock);
        return store;
    }

    private static ObjectNode deviceByCode(ObjectNode store, String code) {
        for (JsonNode entry : array(store, "devices")) if (code.equals(entry.path("code").asText())) return (ObjectNode) entry;
        throw new IllegalStateException("Seed device missing: " + code);
    }

    static JsonNode mutate(ObjectNode store, String action, String id, JsonNode input, String actorId, Clock clock) {
        ObjectNode actor = actor(actorId);
        String at = clock.instant().toString();
        String currentDate = today(clock);
        ObjectNode result;
        switch (action) {
            case "createDevice" -> {
                admin(actor);
                fields(input, "code", "name", "location", "category");
                String code = text(input, "code", "设备编码", 40, false);
                for (JsonNode device : array(store, "devices")) {
                    if (code.equalsIgnoreCase(device.path("code").asText())) throw new DemoException(409, "DUPLICATE_CODE", "设备编码已存在");
                }
                result = object("id", uuid(), "code", code,
                        "name", text(input, "name", "设备名称", 80, false),
                        "location", text(input, "location", "位置", 80, false),
                        "category", text(input, "category", "分类", 40, false),
                        "status", "active", "version", 1, "createdAt", at);
                array(store, "devices").insert(0, result);
                event(store, actor, "device", result, "created", "建立设备 " + code, at);
                return result;
            }
            case "createOrder" -> {
                fields(input, "deviceId", "title", "description", "priority");
                String deviceId = text(input, "deviceId", "设备 ID", 80, false);
                find(array(store, "devices"), deviceId, "设备");
                return newOrder(store, deviceId, text(input, "title", "故障标题", 100, false),
                        text(input, "description", "故障描述", 1000, false),
                        choice(input, "priority", "优先级", "normal", "urgent"), actor, at, null);
            }
            case "assignOrder", "acceptOrder", "rejectOrder", "completeOrder", "reviewOrder", "cancelOrder" -> {
                switch (action) {
                    case "assignOrder" -> fields(input, "version", "assigneeId");
                    case "acceptOrder" -> fields(input, "version");
                    case "rejectOrder", "cancelOrder" -> fields(input, "version", "reason");
                    case "completeOrder" -> fields(input, "version", "summary", "workMinutes", "materials", "safetyConfirmed");
                    case "reviewOrder" -> fields(input, "version", "decision", "note");
                }
                result = find(array(store, "orders"), id, "工单");
                version(result, input);
                String number = result.path("number").asText();
                String assigneeId = result.path("assigneeId").isNull() ? null : result.path("assigneeId").asText();
                switch (action) {
                    case "assignOrder" -> {
                        admin(actor);
                        requireStatus(result, "pending_assignment", "pending_acceptance");
                        String target = text(input, "assigneeId", "维修人员", 80, false);
                        if (!"technician".equals(actor(target).path("role").asText())) throw bad("工单必须指派给维修人员");
                        result.put("assigneeId", target);
                        result.put("status", "pending_acceptance");
                        event(store, actor, "order", result, "assigned", number + " 已指派给 " + actor(target).path("name").asText(), at, target);
                    }
                    case "acceptOrder" -> {
                        executor(actor, assigneeId);
                        requireStatus(result, "pending_acceptance");
                        result.put("status", "in_progress");
                        event(store, actor, "order", result, "accepted", number + " 已接单，开始维修", at, result.path("reporterId").asText());
                    }
                    case "rejectOrder" -> {
                        executor(actor, assigneeId);
                        requireStatus(result, "pending_acceptance");
                        String reason = text(input, "reason", "拒单原因", 500, false);
                        result.put("status", "pending_assignment");
                        result.putNull("assigneeId");
                        event(store, actor, "order", result, "rejected", number + " 拒单：" + reason, at, "admin");
                    }
                    case "completeOrder" -> {
                        executor(actor, assigneeId);
                        requireStatus(result, "in_progress");
                        if (!input.path("safetyConfirmed").isBoolean() || !input.path("safetyConfirmed").booleanValue()) {
                            throw new DemoException(400, "SAFETY_CONFIRMATION_REQUIRED", "请确认已完成演示中的现场安全检查");
                        }
                        String summary = text(input, "summary", "维修结果", 1000, false);
                        int minutes = integer(input, "workMinutes", "工时分钟", 1, 10080);
                        String materials = text(input, "materials", "耗材记录", 500, true);
                        array(result, "completions").add(object("summary", summary, "workMinutes", minutes,
                                "materials", materials, "actorId", actorId, "at", at));
                        result.put("status", "pending_review");
                        event(store, actor, "order", result, "completed", number + " 已提交完工，等待验收", at, "admin", result.path("reporterId").asText());
                    }
                    case "reviewOrder" -> {
                        if (!"admin".equals(actor.path("role").asText()) && !actorId.equals(result.path("reporterId").asText())) {
                            throw new DemoException(403, "FORBIDDEN", "只有报修人或管理员可验收");
                        }
                        requireStatus(result, "pending_review");
                        String decision = choice(input, "decision", "验收结果", "accept", "return");
                        String note = text(input, "note", "验收说明", 500, false);
                        array(result, "reviews").add(object("decision", decision, "note", note, "actorId", actorId, "at", at));
                        result.put("status", "accept".equals(decision) ? "closed" : "in_progress");
                        if ("accept".equals(decision)) result.put("closedAt", at);
                        event(store, actor, "order", result, "reviewed",
                                number + ("accept".equals(decision) ? " 验收通过：" : " 退回维修：") + note,
                                at, assigneeId, result.path("reporterId").asText());
                    }
                    case "cancelOrder" -> {
                        if (!"admin".equals(actor.path("role").asText()) && !actorId.equals(result.path("reporterId").asText())) {
                            throw new DemoException(403, "FORBIDDEN", "只有报修人或管理员可取消");
                        }
                        if ("admin".equals(actor.path("role").asText())) requireStatus(result, "pending_assignment", "pending_acceptance", "in_progress");
                        else requireStatus(result, "pending_assignment");
                        String reason = text(input, "reason", "取消原因", 500, false);
                        result.put("status", "cancelled");
                        event(store, actor, "order", result, "cancelled", number + " 已取消：" + reason,
                                at, result.path("reporterId").asText(), assigneeId);
                    }
                }
                result.put("version", result.path("version").asInt() + 1);
                result.put("updatedAt", at);
                return result;
            }
            default -> { return mutateRemainder(store, action, id, input, actor, at, currentDate, clock); }
        }
    }

    private static JsonNode mutateRemainder(ObjectNode store, String action, String id, JsonNode input,
                                            ObjectNode actor, String at, String currentDate, Clock clock) {
        String actorId = actor.path("id").asText();
        switch (action) {
            case "createPlan" -> {
                admin(actor);
                fields(input, "deviceId", "name", "type", "intervalDays", "nextDueDate", "assigneeId", "checklist");
                String deviceId = text(input, "deviceId", "设备 ID", 80, false);
                find(array(store, "devices"), deviceId, "设备");
                String target = text(input, "assigneeId", "执行人员", 80, false);
                String targetRole = actor(target).path("role").asText();
                if (!Set.of("technician", "inspector").contains(targetRole)) throw bad("计划必须指派给维修工或巡检员");
                JsonNode rawChecklist = input.get("checklist");
                if (!(rawChecklist instanceof ArrayNode checklist) || checklist.size() < 1 || checklist.size() > 20) {
                    throw bad("检查项须为 1–20 项");
                }
                ArrayNode labels = JSON.createArrayNode();
                Set<String> unique = new HashSet<>();
                for (JsonNode item : checklist) {
                    String label = text(object("item", item), "item", "检查项", 100, false);
                    if (!unique.add(label)) throw bad("检查项名称不能重复");
                    labels.add(label);
                }
                ObjectNode plan = object("id", uuid(), "deviceId", deviceId,
                        "name", text(input, "name", "计划名称", 100, false),
                        "type", choice(input, "type", "计划类型", "maintenance", "inspection"),
                        "intervalDays", integer(input, "intervalDays", "周期天数", 1, 3650),
                        "nextDueDate", date(input, "nextDueDate", "首次到期日"),
                        "assigneeId", target, "checklist", labels,
                        "active", true, "version", 1, "createdAt", at);
                array(store, "plans").insert(0, plan);
                event(store, actor, "plan", plan, "created", "建立计划 " + plan.path("name").asText(), at);
                return plan;
            }
            case "togglePlan" -> {
                admin(actor);
                fields(input, "version", "active");
                ObjectNode plan = find(array(store, "plans"), id, "计划");
                version(plan, input);
                if (!input.path("active").isBoolean()) throw bad("active 必须为布尔值");
                boolean active = input.path("active").booleanValue();
                plan.put("active", active);
                plan.put("version", plan.path("version").asInt() + 1);
                event(store, actor, "plan", plan, "updated", plan.path("name").asText() + (active ? " 已启用" : " 已暂停"), at);
                return plan;
            }
            case "generateTasks" -> {
                admin(actor);
                fields(input, "throughDate");
                String through = date(input, "throughDate", "生成截止日期");
                if (through.compareTo(currentDate) > 0) throw bad("演示仅生成截至今天的到期任务");
                record PlanDue(ObjectNode plan, String due) {}
                List<PlanDue> pending = new ArrayList<>();
                List<PlanDue> advances = new ArrayList<>();
                for (JsonNode rawPlan : array(store, "plans")) {
                    ObjectNode plan = (ObjectNode) rawPlan;
                    if (!plan.path("active").asBoolean()) continue;
                    String due = plan.path("nextDueDate").asText();
                    while (due.compareTo(through) <= 0) {
                        boolean existing = false;
                        for (JsonNode task : array(store, "tasks")) {
                            if (plan.path("id").asText().equals(task.path("planId").asText()) && due.equals(task.path("scheduledDate").asText())) {
                                existing = true;
                                break;
                            }
                        }
                        if (!existing) pending.add(new PlanDue(plan, due));
                        if (pending.size() > 100) throw new DemoException(400, "GENERATION_LIMIT", "单次生成最多 100 项，请缩小日期范围");
                        due = LocalDate.parse(due).plusDays(plan.path("intervalDays").asInt()).toString();
                    }
                    advances.add(new PlanDue(plan, due));
                }
                ArrayNode generated = JSON.createArrayNode();
                for (PlanDue entry : pending) {
                    ObjectNode plan = entry.plan();
                    String taskId = uuid();
                    ObjectNode counters = (ObjectNode) store.get("counters");
                    int number = counters.path("task").asInt() + 1;
                    counters.put("task", number);
                    ArrayNode checklist = JSON.createArrayNode();
                    for (int index = 0; index < plan.path("checklist").size(); index++) {
                        checklist.add(object("id", taskId + "-" + index, "label", plan.path("checklist").get(index).asText()));
                    }
                    ObjectNode task = object("id", taskId, "number", "PM-" + String.format("%04d", number),
                            "planId", plan.path("id").asText(), "deviceId", plan.path("deviceId").asText(),
                            "name", plan.path("name").asText(), "type", plan.path("type").asText(),
                            "scheduledDate", entry.due(), "assigneeId", plan.path("assigneeId").asText(),
                            "checklist", checklist, "results", JSON.createArrayNode(), "status", "pending",
                            "version", 1, "createdAt", at, "completedAt", null, "linkedOrderId", null,
                            "note", "", "history", JSON.createArrayNode());
                    array(store, "tasks").insert(0, task);
                    event(store, actor, "task", task, "generated",
                            task.path("number").asText() + "：" + task.path("name").asText() + " 到期 " + entry.due(),
                            at, task.path("assigneeId").asText());
                    generated.add(task);
                }
                for (PlanDue entry : advances) {
                    ObjectNode plan = entry.plan();
                    if (!plan.path("nextDueDate").asText().equals(entry.due())) {
                        plan.put("nextDueDate", entry.due());
                        plan.put("version", plan.path("version").asInt() + 1);
                        event(store, actor, "plan", plan, "advanced",
                                plan.path("name").asText() + " 下次到期 " + entry.due(), at);
                    }
                }
                return object("generated", generated, "count", generated.size(), "throughDate", through);
            }
            case "completeTask" -> {
                fields(input, "version", "results", "note");
                ObjectNode task = find(array(store, "tasks"), id, "维保任务");
                executor(actor, task.path("assigneeId").asText());
                version(task, input);
                requireStatus(task, "pending");
                JsonNode raw = input.get("results");
                if (!(raw instanceof ArrayNode answers) || answers.size() != task.path("checklist").size()) {
                    throw bad("须提交全部检查项");
                }
                Set<String> ids = new HashSet<>();
                for (JsonNode answer : answers) {
                    fields(answer, "itemId", "verdict", "reading", "remark");
                    String itemId = text(answer, "itemId", "检查项 ID", 100, false);
                    if (!ids.add(itemId)) throw bad("检查项不能重复");
                }
                ArrayNode results = JSON.createArrayNode();
                List<String> abnormalDescriptions = new ArrayList<>();
                for (JsonNode item : task.path("checklist")) {
                    JsonNode answer = null;
                    for (JsonNode candidate : answers) {
                        if (item.path("id").asText().equals(candidate.path("itemId").asText())) {
                            answer = candidate;
                            break;
                        }
                    }
                    if (answer == null) throw bad("缺少检查项：" + item.path("label").asText());
                    String verdict = choice(answer, "verdict", "检查结果", "normal", "abnormal");
                    String reading = text(answer, "reading", "读数", 100, true);
                    String remark = text(answer, "remark", "说明", 500, "normal".equals(verdict));
                    String label = item.path("label").asText();
                    results.add(object("itemId", item.path("id").asText(), "label", label,
                            "verdict", verdict, "reading", reading, "remark", remark));
                    if ("abnormal".equals(verdict)) {
                        abnormalDescriptions.add(label + "：" + remark + (reading.isEmpty() ? "" : "（读数 " + reading + "）"));
                    }
                }
                task.put("note", text(input, "note", "作业备注", 1000, true));
                if (!abnormalDescriptions.isEmpty()) {
                    String title = task.path("name").asText() + "发现异常";
                    if (title.length() > 100) title = title.substring(0, 100);
                    ObjectNode linked = newOrder(store, task.path("deviceId").asText(), title,
                            String.join("\n", abnormalDescriptions), "normal", actor, at, task.path("id").asText());
                    task.put("linkedOrderId", linked.path("id").asText());
                }
                task.set("results", results);
                task.put("status", "completed");
                task.put("completedAt", at);
                task.put("version", task.path("version").asInt() + 1);
                event(store, actor, "task", task, "completed",
                        task.path("number").asText() + " 已完成" + (abnormalDescriptions.isEmpty() ? "" : "，异常已关联维修工单"),
                        at, "admin");
                return task;
            }
            case "readMessage" -> {
                fields(input);
                ObjectNode message = find(array(store, "messages"), id, "消息");
                if (!actorId.equals(message.path("recipient").asText())) throw new DemoException(403, "FORBIDDEN", "只能修改自己的消息");
                message.put("read", true);
                return message;
            }
            default -> throw new DemoException(404, "NOT_FOUND", "未知操作");
        }
    }

    private static String derivedStatus(ObjectNode store, String deviceId) {
        boolean hasOpen = false;
        for (JsonNode order : array(store, "orders")) {
            if (!deviceId.equals(order.path("deviceId").asText())) continue;
            String status = order.path("status").asText();
            if (Set.of("closed", "cancelled").contains(status)) continue;
            if (Set.of("in_progress", "pending_review").contains(status)) return "repairing";
            hasOpen = true;
        }
        return hasOpen ? "reported" : "active";
    }

    private static ArrayNode devices(ObjectNode store, String q) {
        ArrayNode result = JSON.createArrayNode();
        String needle = q == null ? "" : q.toLowerCase();
        for (JsonNode raw : array(store, "devices")) {
            ObjectNode device = (ObjectNode) raw;
            String searchable = (device.path("code").asText() + " " + device.path("name").asText() + " " + device.path("location").asText()).toLowerCase();
            if (searchable.contains(needle)) {
                ObjectNode visible = device.deepCopy();
                visible.put("derivedStatus", derivedStatus(store, device.path("id").asText()));
                result.add(visible);
            }
        }
        return result;
    }

    private static ObjectNode summary(ObjectNode store, Clock clock) {
        String day = today(clock);
        int openOrders = 0, pendingReview = 0, closedOrders = 0, pendingTasks = 0, overdueTasks = 0;
        for (JsonNode order : array(store, "orders")) {
            String status = order.path("status").asText();
            if (!Set.of("closed", "cancelled").contains(status)) openOrders++;
            if ("pending_review".equals(status)) pendingReview++;
            if ("closed".equals(status)) closedOrders++;
        }
        for (JsonNode task : array(store, "tasks")) {
            if ("pending".equals(task.path("status").asText())) {
                pendingTasks++;
                if (task.path("scheduledDate").asText().compareTo(day) < 0) overdueTasks++;
            }
        }
        return object("devices", array(store, "devices").size(), "openOrders", openOrders,
                "pendingReview", pendingReview, "pendingTasks", pendingTasks,
                "overdueTasks", overdueTasks, "closedOrders", closedOrders, "today", day);
    }

    static JsonNode read(ObjectNode store, String handler, String id, Map<String, String> query, String actorId, Clock clock) {
        ObjectNode actor = actor(actorId);
        switch (handler) {
            case "health" -> { return object("mode", "demo", "runtime", "java-servlet", "version", "0.2.0", "persistence", "json-file", "today", today(clock)); }
            case "actors" -> { return actors(); }
            case "summary" -> { return summary(store, clock); }
            case "devices" -> { return devices(store, query.get("q")); }
            case "deviceHistory" -> {
                ObjectNode device = find(devices(store, ""), id, "设备");
                ArrayNode orders = JSON.createArrayNode(), tasks = JSON.createArrayNode(), events = JSON.createArrayNode();
                Set<String> ids = new HashSet<>();
                ids.add(id);
                for (JsonNode order : array(store, "orders")) if (id.equals(order.path("deviceId").asText())) { orders.add(order); ids.add(order.path("id").asText()); }
                for (JsonNode task : array(store, "tasks")) if (id.equals(task.path("deviceId").asText())) { tasks.add(task); ids.add(task.path("id").asText()); }
                for (JsonNode plan : array(store, "plans")) if (id.equals(plan.path("deviceId").asText())) ids.add(plan.path("id").asText());
                for (JsonNode event : array(store, "audit")) if (ids.contains(event.path("entityId").asText())) events.add(event);
                return object("device", device, "orders", orders, "tasks", tasks, "events", events);
            }
            case "orders" -> {
                String status = query.get("status"), deviceId = query.get("deviceId");
                if (status != null && !status.isEmpty() && !ORDER_STATES.contains(status)) throw bad("工单状态无效");
                ArrayNode result = JSON.createArrayNode();
                for (JsonNode order : array(store, "orders")) {
                    if ((status == null || status.isEmpty() || status.equals(order.path("status").asText()))
                            && (deviceId == null || deviceId.isEmpty() || deviceId.equals(order.path("deviceId").asText()))) result.add(order);
                }
                return result;
            }
            case "order" -> { return find(array(store, "orders"), id, "工单"); }
            case "plans" -> { return array(store, "plans"); }
            case "tasks" -> {
                String status = query.get("status");
                if (status != null && !status.isEmpty() && !Set.of("pending", "completed").contains(status)) throw bad("任务状态无效");
                ArrayNode result = JSON.createArrayNode();
                for (JsonNode task : array(store, "tasks")) if (status == null || status.isEmpty() || status.equals(task.path("status").asText())) result.add(task);
                return result;
            }
            case "task" -> { return find(array(store, "tasks"), id, "任务"); }
            case "messages" -> {
                ArrayNode result = JSON.createArrayNode();
                for (JsonNode message : array(store, "messages")) if (actorId.equals(message.path("recipient").asText())) result.add(message);
                return result;
            }
            case "audit" -> {
                admin(actor);
                return array(store, "audit");
            }
            case "snapshot" -> {
                ArrayNode messages = JSON.createArrayNode();
                for (JsonNode message : array(store, "messages")) if (actorId.equals(message.path("recipient").asText())) messages.add(message);
                return object("actors", actors(), "devices", devices(store, ""), "orders", array(store, "orders"),
                        "plans", array(store, "plans"), "tasks", array(store, "tasks"), "messages", messages,
                        "audit", "admin".equals(actor.path("role").asText()) ? array(store, "audit") : JSON.createArrayNode(),
                        "summary", summary(store, clock));
            }
            default -> throw new DemoException(404, "NOT_FOUND", "未知操作");
        }
    }
}
