package com.equipmentmaintenance.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** One-process demonstration store. Not a database or a multi-instance transaction manager. */
final class DemoStore {
    private final Path file;
    private final Clock clock;
    private ObjectNode current;

    DemoStore(Path file, Clock clock) throws IOException {
        this.file = file.toAbsolutePath().normalize();
        this.clock = clock;
        Files.createDirectories(this.file.getParent());
        if (Files.exists(this.file)) {
            JsonNode loaded = DemoDomain.JSON.readTree(Files.readString(this.file, StandardCharsets.UTF_8));
            validate(loaded);
            current = (ObjectNode) loaded;
        } else {
            ObjectNode initial = DemoDomain.seed(clock);
            persist(initial);
            current = initial;
        }
    }

    static Path defaultPath() {
        String configured = System.getProperty("equipment.demo.data");
        if (configured == null || configured.isBlank()) configured = System.getenv("EQUIPMENT_DEMO_DATA");
        if (configured != null && !configured.isBlank()) return Path.of(configured);
        String base = System.getProperty("catalina.base", System.getProperty("user.dir"));
        return Path.of(base, "data", "equipment-maintenance-demo", "demo.json");
    }

    private static void validate(JsonNode store) throws IOException {
        if (!(store instanceof ObjectNode) || store.path("schemaVersion").asInt(-1) != 1
                || !store.path("counters").isObject()
                || !store.path("counters").path("order").isIntegralNumber()
                || !store.path("counters").path("task").isIntegralNumber()) {
            throw new IOException("演示数据版本或结构不受支持；原文件已保留。");
        }
        for (String key : new String[]{"devices", "orders", "plans", "tasks", "messages", "audit"}) {
            if (!(store.path(key) instanceof ArrayNode array)) throw new IOException("演示数据缺少 " + key + "；原文件已保留。");
            Set<String> ids = new HashSet<>();
            for (JsonNode item : array) {
                if (!item.isObject() || !item.path("id").isTextual() || !ids.add(item.path("id").asText())) {
                    throw new IOException("演示数据 " + key + " 含无效或重复 ID；原文件已保留。");
                }
            }
        }
        for (JsonNode order : store.path("orders")) {
            if (!DemoDomain.ORDER_STATES.contains(order.path("status").asText())
                    || !order.path("completions").isArray() || !order.path("reviews").isArray()
                    || !order.path("history").isArray() || !order.path("version").isIntegralNumber()) {
                throw new IOException("演示工单数据无效；原文件已保留。");
            }
        }
        for (JsonNode plan : store.path("plans")) {
            if (!plan.path("checklist").isArray() || plan.path("intervalDays").asInt(0) < 1
                    || !plan.path("nextDueDate").asText().matches("\\d{4}-\\d{2}-\\d{2}")) {
                throw new IOException("演示计划数据无效；原文件已保留。");
            }
        }
        for (JsonNode task : store.path("tasks")) {
            if (!Set.of("pending", "completed").contains(task.path("status").asText())
                    || !task.path("checklist").isArray() || !task.path("history").isArray()) {
                throw new IOException("演示维保任务数据无效；原文件已保留。");
            }
        }
    }

    private void persist(ObjectNode next) throws IOException {
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary, DemoDomain.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(next), StandardCharsets.UTF_8);
        try {
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    synchronized JsonNode read(String handler, String id, Map<String, String> query, String actorId) {
        return DemoDomain.read(current, handler, id, query, actorId, clock).deepCopy();
    }

    synchronized JsonNode mutate(String handler, String id, JsonNode input, String actorId) throws IOException {
        ObjectNode next = current.deepCopy();
        JsonNode result = DemoDomain.mutate(next, handler, id, input, actorId, clock);
        persist(next);
        current = next;
        return result.deepCopy();
    }

    Path file() { return file; }
}
