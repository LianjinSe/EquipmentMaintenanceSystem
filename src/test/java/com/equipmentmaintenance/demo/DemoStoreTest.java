package com.equipmentmaintenance.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DemoStoreTest {
    private static final Clock NOW = Clock.fixed(Instant.parse("2026-09-27T02:00:00Z"), ZoneOffset.UTC);
    @TempDir Path directory;

    private DemoStore newStore() throws IOException { return new DemoStore(directory.resolve("demo.json"), NOW); }
    private static ObjectNode o(Object... pairs) { return DemoDomain.object(pairs); }
    private static JsonNode read(DemoStore store, String handler) { return store.read(handler, null, Map.of(), "admin"); }
    private static JsonNode mutation(DemoStore store, String action, String id, JsonNode payload, String actor) throws IOException {
        return store.mutate(action, id, payload, actor);
    }

    @Test
    void seedProducesWorkingAssetsPlansAndTasks() throws IOException {
        DemoStore store = newStore();
        JsonNode state = read(store, "snapshot");
        assertEquals(3, state.path("devices").size());
        assertEquals(1, state.path("orders").size());
        assertEquals(2, state.path("plans").size());
        assertEquals(3, state.path("tasks").size());
        assertEquals("2026-09-27", state.path("summary").path("today").asText());
        assertEquals(1, state.path("summary").path("overdueTasks").asInt());
    }

    @Test
    void repairCanBeReturnedAndAcceptedWithoutLosingHistory() throws IOException {
        DemoStore store = newStore();
        String deviceId = read(store, "devices").get(0).path("id").asText();
        JsonNode order = mutation(store, "createOrder", null,
                o("deviceId", deviceId, "title", "测试故障", "description", "记录故障现象", "priority", "normal"), "operator");
        String id = order.path("id").asText();
        order = mutation(store, "assignOrder", id, o("version", order.path("version").asInt(), "assigneeId", "technician"), "admin");
        JsonNode pending = order;
        DemoException jumped = assertThrows(DemoException.class, () -> mutation(store, "completeOrder", id,
                o("version", pending.path("version").asInt(), "summary", "不可跳过接单", "workMinutes", 10, "safetyConfirmed", true), "technician"));
        assertEquals(409, jumped.status);
        order = mutation(store, "acceptOrder", id, o("version", order.path("version").asInt()), "technician");
        order = mutation(store, "completeOrder", id, o("version", order.path("version").asInt(), "summary", "首次完工",
                "workMinutes", 15, "materials", "密封圈", "safetyConfirmed", true), "technician");
        order = mutation(store, "reviewOrder", id, o("version", order.path("version").asInt(), "decision", "return", "note", "仍有异响"), "operator");
        assertEquals("in_progress", order.path("status").asText());
        order = mutation(store, "completeOrder", id, o("version", order.path("version").asInt(), "summary", "二次完工",
                "workMinutes", 25, "safetyConfirmed", true), "technician");
        order = mutation(store, "reviewOrder", id, o("version", order.path("version").asInt(), "decision", "accept", "note", "复查合格"), "operator");
        assertEquals("closed", order.path("status").asText());
        assertEquals(2, order.path("completions").size());
        assertEquals(2, order.path("reviews").size());
        assertTrue(order.path("history").size() >= 7);
        assertFalse(order.path("closedAt").isNull());
        JsonNode closed = order;
        assertEquals("INVALID_STATE", assertThrows(DemoException.class, () -> mutation(store, "cancelOrder", id,
                o("version", closed.path("version").asInt(), "reason", "已关闭"), "admin")).code);
    }

    @Test
    void rolesAndVersionsBlockInvalidActions() throws IOException {
        DemoStore store = newStore();
        JsonNode order = read(store, "orders").get(0);
        String id = order.path("id").asText();
        assertEquals(403, assertThrows(DemoException.class, () -> mutation(store, "assignOrder", id,
                o("version", order.path("version").asInt(), "assigneeId", "technician"), "operator")).status);
        JsonNode assigned = mutation(store, "assignOrder", id, o("version", order.path("version").asInt(), "assigneeId", "technician"), "admin");
        assertEquals(409, assertThrows(DemoException.class, () -> mutation(store, "assignOrder", id,
                o("version", order.path("version").asInt(), "assigneeId", "technician"), "admin")).status);
        assertEquals(403, assertThrows(DemoException.class, () -> mutation(store, "acceptOrder", id,
                o("version", assigned.path("version").asInt()), "inspector")).status);
        JsonNode rejected = mutation(store, "rejectOrder", id,
                o("version", assigned.path("version").asInt(), "reason", "需重新安排"), "technician");
        assertEquals("pending_assignment", rejected.path("status").asText());
        assertTrue(rejected.path("assigneeId").isNull());
    }

    @Test
    void taskGenerationIsRepeatableAndBadInputDoesNotPartiallySave() throws IOException {
        DemoStore store = newStore();
        assertEquals(0, mutation(store, "generateTasks", null, o("throughDate", "2026-09-27"), "admin").path("count").asInt());
        String deviceId = read(store, "devices").get(0).path("id").asText();
        assertEquals(400, assertThrows(DemoException.class, () -> mutation(store, "createPlan", null,
                o("deviceId", deviceId, "name", "非法日期", "type", "inspection", "intervalDays", 1,
                        "nextDueDate", "2026-02-30", "assigneeId", "inspector", "checklist", new String[]{"油位"}), "admin")).status);
        JsonNode plan = mutation(store, "createPlan", null,
                o("deviceId", deviceId, "name", "测试点检", "type", "inspection", "intervalDays", 2,
                        "nextDueDate", "2026-09-25", "assigneeId", "inspector", "checklist", new String[]{"油位"}), "admin");
        assertEquals(2, mutation(store, "generateTasks", null, o("throughDate", "2026-09-27"), "admin").path("count").asInt());
        assertEquals(0, mutation(store, "generateTasks", null, o("throughDate", "2026-09-27"), "admin").path("count").asInt());
        JsonNode updated = read(store, "plans");
        JsonNode testPlan = null;
        for (JsonNode entry : updated) if (entry.path("id").asText().equals(plan.path("id").asText())) testPlan = entry;
        assertNotNull(testPlan);
        mutation(store, "togglePlan", plan.path("id").asText(), o("version", testPlan.path("version").asInt(), "active", false), "admin");
        long prior = read(store, "tasks").size();
        mutation(store, "createPlan", null,
                o("deviceId", deviceId, "name", "超限回补", "type", "inspection", "intervalDays", 1,
                        "nextDueDate", "2026-01-01", "assigneeId", "inspector", "checklist", new String[]{"项目"}), "admin");
        assertEquals("GENERATION_LIMIT", assertThrows(DemoException.class, () -> mutation(store, "generateTasks", null,
                o("throughDate", "2026-09-27"), "admin")).code);
        assertEquals(prior, read(store, "tasks").size());
    }

    @Test
    void abnormalTaskCreatesExactlyOneLinkedOrderAtomically() throws IOException {
        DemoStore store = newStore();
        JsonNode task = null;
        for (JsonNode entry : read(store, "tasks")) if ("inspection".equals(entry.path("type").asText())) { task = entry; break; }
        assertNotNull(task);
        String taskId = task.path("id").asText();
        int priorOrders = read(store, "orders").size();
        ArrayNode answers = DemoDomain.JSON.createArrayNode();
        int index = 0;
        for (JsonNode item : task.path("checklist")) {
            answers.add(o("itemId", item.path("id").asText(), "verdict", index++ == 0 ? "abnormal" : "normal",
                    "reading", "", "remark", ""));
        }
        JsonNode original = task;
        assertEquals(400, assertThrows(DemoException.class, () -> mutation(store, "completeTask", taskId,
                o("version", original.path("version").asInt(), "results", answers, "note", ""), "inspector")).status);
        assertEquals(priorOrders, read(store, "orders").size());
        ((ObjectNode) answers.get(0)).put("remark", "油位异常");
        JsonNode completed = mutation(store, "completeTask", taskId,
                o("version", task.path("version").asInt(), "results", answers, "note", "作业记录"), "inspector");
        assertEquals("completed", completed.path("status").asText());
        assertFalse(completed.path("linkedOrderId").isNull());
        assertEquals(priorOrders + 1, read(store, "orders").size());
        assertEquals(taskId, store.read("order", completed.path("linkedOrderId").asText(), Map.of(), "admin").path("sourceTaskId").asText());
        assertEquals(409, assertThrows(DemoException.class, () -> mutation(store, "completeTask", taskId,
                o("version", original.path("version").asInt(), "results", answers, "note", ""), "inspector")).status);
    }

    @Test
    void restartPreservesDataAndFailedWriteDoesNotPublishChange() throws IOException {
        DemoStore store = newStore();
        JsonNode added = mutation(store, "createDevice", null,
                o("code", "NEW-001", "name", "测试设备", "location", "测试位置", "category", "测试"), "admin");
        DemoStore reopened = newStore();
        assertEquals(4, read(reopened, "devices").size());
        assertEquals(added.path("id").asText(), read(reopened, "devices").get(0).path("id").asText());
        Path temporary = directory.resolve("demo.json.tmp");
        Files.createDirectory(temporary);
        assertThrows(IOException.class, () -> mutation(store, "createDevice", null,
                o("code", "FAILED", "name", "不应保存", "location", "测试位置", "category", "测试"), "admin"));
        assertEquals(4, read(store, "devices").size());
        assertFalse(Files.readString(directory.resolve("demo.json"), StandardCharsets.UTF_8).contains("FAILED"));
    }

    @Test
    void corruptFileIsKeptAndDoesNotTriggerReseed() throws IOException {
        DemoStore store = newStore();
        Files.writeString(store.file(), "{invalid", StandardCharsets.UTF_8);
        assertThrows(IOException.class, this::newStore);
        assertEquals("{invalid", Files.readString(store.file(), StandardCharsets.UTF_8));
    }
}
