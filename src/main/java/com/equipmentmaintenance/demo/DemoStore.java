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
    private final FileVault vault;

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
        vault = new FileVault(this.file);
        if (FeatureDomain.initialize(current, this.file, clock)) persist(current);
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

    synchronized String authenticate(String token) { return FeatureAuth.authenticate(current, token, clock); }

    synchronized FeatureResult feature(String code, String id, JsonNode input, String actorId, Map<String,String> query) throws IOException {
        ObjectNode next=current.deepCopy();
        FeatureResult result;
        if(code.equals("BACKUP")) {
            FeatureSupport.admin(actorId); FeatureAuth.authorize(next,actorId,"BACKUP");
            FeatureSupport.fields(input,"kind","scope","restorePointId","confirm");
            FeatureSupport.choice(input,"scope","database-and-files");
            String kind=FeatureSupport.choice(input,"kind","backup","restore");
            if(kind.equals("backup")) result=FeatureResult.ok(vault.backup(next,actorId,clock));
            else {
                FeatureSupport.require(input.path("confirm").isBoolean() && input.path("confirm").asBoolean(),"恢复须显式 confirm=true，并会撤销当前会话");
                String point=FeatureSupport.text(input,"restorePointId",80);
                ObjectNode restored=vault.restore(point); validate(restored);
                FeatureSupport.require(restored.has("features"),"恢复点不含功能数据");
                vault.restoreFiles(point,restored);
                FeatureSupport.features(restored).set("sessions",FeatureSupport.empty());
                FeatureSupport.features(restored).set("backups",FeatureSupport.rows(next,"backups").deepCopy());
                FeatureSupport.audit(restored,actorId,"backup",point,"restored",clock);
                next=restored;
                result=FeatureResult.ok(DemoDomain.object("status","restored","restorePointId",point,"sessionsRevoked",true,"verification","data-and-file-hashes-verified"));
            }
        } else result=FeatureDomain.execute(next,code,id,input,actorId,query,clock,vault);
        persist(next); current=next;
        return new FeatureResult(result.status(),result.data().deepCopy());
    }

    synchronized JsonNode upload(String id, byte[] bytes, String contentType, String actor) throws IOException {
        ObjectNode next=current.deepCopy(), record=FeatureSupport.ref(next,"files",id);
        FeatureAuth.authorize(next,actor,"FILE");
        if(!actor.equals("admin") && !actor.equals(record.path("createdBy").asText())) throw FeatureSupport.fail(403,"FORBIDDEN","只有上传申请人可写入文件");
        FeatureSupport.require(contentType!=null && contentType.split(";")[0].trim().equals(record.path("contentType").asText()),"上传类型与申请不一致");
        FeatureSupport.require(bytes.length==record.path("size").asInt(),"上传字节数与申请不一致");
        String hash=FeatureAuth.digest(bytes);
        if(record.path("status").asText().equals("uploaded")) {
            if(!hash.equals(record.path("sha256").asText())) throw FeatureSupport.fail(409,"IMMUTABLE_FILE","已上传文件不能覆盖");
            return record.deepCopy();
        }
        if(!java.time.Instant.parse(record.path("expiresAt").asText()).isAfter(clock.instant())) throw FeatureSupport.fail(410,"UPLOAD_EXPIRED","上传申请已过期");
        FileVault.content(record.path("contentType").asText(),bytes); vault.write(id,bytes);
        record.put("status","uploaded");record.put("sha256",hash);record.put("version",record.path("version").asInt()+1);
        FeatureSupport.audit(next,actor,"files",id,"uploaded",clock);persist(next);current=next;return record.deepCopy();
    }

    record Download(byte[] bytes,String filename,String contentType) {}
    synchronized Download download(String id,String actor) throws IOException {
        FeatureAuth.authorize(current,actor,"FILE");ObjectNode file=FeatureBusiness.uploaded(current,id,actor);
        if(file.has("downloadExpiresAt") && !java.time.Instant.parse(file.path("downloadExpiresAt").asText()).isAfter(clock.instant())) throw FeatureSupport.fail(410,"DOWNLOAD_EXPIRED","下载已过期");
        byte[] bytes=vault.read(id);
        if(!FeatureAuth.digest(bytes).equals(file.path("sha256").asText())) throw FeatureSupport.fail(409,"FILE_INTEGRITY_ERROR","文件摘要校验失败");
        return new Download(bytes,file.path("filename").asText(),file.path("contentType").asText());
    }
}
