package com.equipmentmaintenance.demo;

import static com.equipmentmaintenance.demo.FeatureSupport.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.io.IOException;
import java.time.*;
import java.util.*;

final class FeatureWork {
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
      case "MOBILE" -> {
        fields(in, "platform", "authorizationCode", "appVersion");
        String platform = choice(in, "platform", "web", "wechat", "alipay", "native");
        text(in, "appVersion", 40);
        if (!platform.equals("web"))
          return FeatureDomain.unavailable(state, "integrationJobs", platform, in, actor, clock);
        return FeatureResult.ok(
            o(
                "session",
                o("actorId", actor, "platform", "web"),
                "permittedFeatures",
                ref(state, "roles", actor).path("operations"),
                "updatePolicy",
                o("minimumVersion", "1", "mode", "local_web")));
      }
      case "OFFLINE" -> {
        return FeatureResult.ok(sync(state, in, actor, clock));
      }
      case "SOP" -> {
        fields(in, "taskId", "templateVersion", "templateId", "steps", "version");
        String taskId = text(in, "taskId", 80);
        entityAccess(state, "task", taskId, actor);
        String templateId = optional(in, "templateId", 80);
        if (templateId.isEmpty()) templateId = "sop-demo";
        ObjectNode template = ref(state, "sopTemplates", templateId);
        require(
            text(in, "templateVersion", 80).equals(template.path("version").asText()), "标准版本不匹配");
        ArrayNode steps = array(in, "steps", 0, 30);
        require(steps.size() <= template.path("steps").size(), "步骤数量超过模板");
        ObjectNode execution = null;
        for (JsonNode row : rows(state, "sopExecutions"))
          if (row.path("entityId").asText().equals(taskId)
              && row.path("templateId").asText().equals(templateId)) execution = (ObjectNode) row;
        if (execution != null) {
          version(execution, in);
          require(!execution.path("status").asText().equals("completed"), "完整 SOP 已归档");
          require(steps.size() >= execution.path("steps").size(), "不能移除已记录步骤");
        }
        for (int i = 0; i < steps.size(); i++) {
          JsonNode step = steps.get(i), expected = template.path("steps").get(i);
          fields(step, "stepId", "checked", "evidenceIds");
          require(
              text(step, "stepId", 80).equals(expected.path("id").asText())
                  && step.path("checked").isBoolean()
                  && step.path("checked").asBoolean(),
              "步骤须按顺序确认，不能跳步");
          ArrayNode evidence =
              array(step, "evidenceIds", expected.path("evidenceRequired").asBoolean() ? 1 : 0, 10);
          for (JsonNode file : evidence) FeatureBusiness.uploaded(state, file.asText(), actor);
        }
        if (execution == null)
          execution =
              add(
                  state,
                  "sopExecutions",
                  o(
                      "entityType",
                      "task",
                      "entityId",
                      taskId,
                      "templateId",
                      templateId,
                      "templateSnapshot",
                      template),
                  actor,
                  clock);
        else execution.put("version", execution.path("version").asInt() + 1);
        execution.set("steps", steps.deepCopy());
        boolean done = steps.size() == template.path("steps").size();
        execution.put("status", done ? "completed" : "in_progress");
        audit(
            state, actor, "sopExecutions", execution.path("id").asText(), "steps-recorded", clock);
        return FeatureResult.ok(
            o(
                "executionId",
                execution.path("id"),
                "version",
                execution.path("version"),
                "nextStep",
                done ? null : template.path("steps").get(steps.size()),
                "status",
                execution.path("status")));
      }
      case "SIGN" -> {
        fields(in, "entityType", "entityId", "version", "signerId", "provider", "evidenceId");
        String type = choice(in, "entityType", "order", "task", "device"),
            entity = text(in, "entityId", 80);
        entityAccess(state, type, entity, actor);
        require(text(in, "signerId", 80).equals(actor), "签署人须为当前登录账号");
        ObjectNode resource =
            core(
                state,
                type.equals("order") ? "orders" : type.equals("task") ? "tasks" : "devices",
                entity);
        version(resource, in);
        if (!text(in, "provider", 80).equals("local"))
          return FeatureDomain.unavailable(
              state, "intelligenceJobs", in.path("provider").asText(), in, actor, clock);
        FeatureBusiness.uploaded(state, text(in, "evidenceId", 80), actor);
        byte[] original = DemoDomain.JSON.writeValueAsBytes(resource);
        String digest = FeatureAuth.digest(original);
        ObjectNode archive =
            o(
                "entityType",
                type,
                "entityId",
                entity,
                "entityVersion",
                resource.path("version"),
                "signerId",
                actor,
                "signedAt",
                clock.instant().toString(),
                "document",
                resource,
                "documentHash",
                digest,
                "localHmac",
                vault.sign(original),
                "signatureType",
                "local_attestation_not_certified_signature");
        ObjectNode file =
            vault.generated(
                state,
                "attestation.json",
                "application/json",
                DemoDomain.JSON.writeValueAsBytes(archive),
                actor,
                clock);
        ObjectNode signature =
            add(
                state,
                "signatures",
                o(
                    "entityType",
                    type,
                    "entityId",
                    entity,
                    "entityVersion",
                    resource.path("version"),
                    "documentHash",
                    digest,
                    "fileId",
                    file.path("id"),
                    "signerId",
                    actor,
                    "evidenceId",
                    in.get("evidenceId")),
                actor,
                clock);
        return new FeatureResult(
            201,
            o(
                "signatureId",
                signature.path("id"),
                "documentHash",
                digest,
                "archivedUrl",
                "api/files/" + file.path("id").asText() + "/content",
                "signatureType",
                "local_attestation"));
      }
      case "devicePosition" -> {
        admin(actor);
        fields(in, "version", "latitude", "longitude", "radiusMeters");
        ObjectNode device = core(state, "devices", id);
        version(device, in);
        device.set(
            "position",
            o(
                "latitude",
                number(in, "latitude", -90, 90),
                "longitude",
                number(in, "longitude", -180, 180),
                "radiusMeters",
                number(in, "radiusMeters", 1, 10000)));
        device.put("version", device.path("version").asInt() + 1);
        audit(state, actor, "device", id, "position-configured", clock);
        return FeatureResult.ok(device);
      }
      case "LOCATION" -> {
        fields(in, "taskId", "deviceId", "position", "proof");
        String taskId = text(in, "taskId", 80), deviceId = text(in, "deviceId", 80);
        entityAccess(state, "task", taskId, actor);
        ObjectNode task = core(state, "tasks", taskId), device = core(state, "devices", deviceId);
        require(task.path("deviceId").asText().equals(deviceId), "任务与设备不匹配");
        ObjectNode label = ref(state, "labels", text(in, "proof", 80));
        require(
            label.path("active").asBoolean() && label.path("deviceId").asText().equals(deviceId),
            "标签已作废或设备不匹配");
        fields(in.path("position"), "latitude", "longitude", "accuracy");
        double lat = number(in.path("position"), "latitude", -90, 90),
            lon = number(in.path("position"), "longitude", -180, 180);
        Double accuracy =
            in.path("position").has("accuracy")
                ? number(in.path("position"), "accuracy", 0, 10000)
                : null;
        Double distance =
            device.has("position")
                ? distance(
                    lat,
                    lon,
                    device.path("position").path("latitude").asDouble(),
                    device.path("position").path("longitude").asDouble())
                : null;
        Boolean matched =
            distance != null && accuracy != null
                ? distance + accuracy <= device.path("position").path("radiusMeters").asDouble()
                : null;
        ObjectNode checkin =
            add(
                state,
                "checkins",
                o(
                    "taskId",
                    taskId,
                    "deviceId",
                    deviceId,
                    "position",
                    in.get("position"),
                    "labelId",
                    label.path("id"),
                    "distanceMeters",
                    distance,
                    "matched",
                    matched,
                    "physicalLocationVerified",
                    false),
                actor,
                clock);
        return new FeatureResult(
            201,
            o(
                "checkinId",
                checkin.path("id"),
                "matched",
                matched,
                "accuracy",
                accuracy,
                "distanceMeters",
                distance,
                "physicalLocationVerified",
                false,
                "note",
                "仅校验提交坐标和标签关联；没有硬件防伪证明"));
      }
      case "OCR" -> {
        fields(in, "taskId", "itemId", "fileId", "meterType");
        String task = text(in, "taskId", 80);
        entityAccess(state, "task", task, actor);
        ObjectNode record = core(state, "tasks", task);
        require(hasItem(record, text(in, "itemId", 100)), "任务没有该检查项");
        ObjectNode file = FeatureBusiness.uploaded(state, text(in, "fileId", 80), actor);
        require(file.path("contentType").asText().startsWith("image/"), "识别来源须为图片");
        text(in, "meterType", 100);
        ObjectNode reading =
            add(
                state,
                "ocrReadings",
                o(
                    "taskId",
                    task,
                    "itemId",
                    in.get("itemId"),
                    "fileId",
                    in.get("fileId"),
                    "meterType",
                    in.get("meterType"),
                    "status",
                    "manual_required",
                    "value",
                    null,
                    "confidence",
                    null),
                actor,
                clock);
        return new FeatureResult(
            202,
            o(
                "readingId",
                reading.path("id"),
                "version",
                1,
                "value",
                null,
                "unit",
                null,
                "confidence",
                null,
                "requiresConfirmation",
                true,
                "status",
                "manual_required",
                "providerStatus",
                "not_configured"));
      }
      case "ocrConfirm" -> {
        fields(in, "version", "value", "unit");
        ObjectNode record = ref(state, "ocrReadings", id);
        entityAccess(state, "task", record.path("taskId").asText(), actor);
        version(record, in);
        require(record.path("status").asText().equals("manual_required"), "读数已确认");
        record.put("value", number(in, "value", -1e12, 1e12));
        record.put("unit", text(in, "unit", 30));
        record.put("status", "confirmed_manually");
        record.put("confirmedBy", actor);
        record.put("version", record.path("version").asInt() + 1);
        audit(state, actor, "ocrReadings", id, "manual-confirmed", clock);
        return FeatureResult.ok(record);
      }
      case "TOOL" -> {
        fields(in, "toolId", "borrowerId", "dueAt", "taskId");
        String borrower = text(in, "borrowerId", 80);
        DemoDomain.actor(borrower);
        require(actor.equals("admin") || actor.equals(borrower), "不能替其他账号借用");
        String task = text(in, "taskId", 80);
        entityAccess(state, "task", task, borrower);
        ObjectNode tool = ref(state, "tools", text(in, "toolId", 80));
        if (!tool.path("status").asText().equals("available"))
          throw fail(409, "TOOL_ALREADY_LOANED", "工具已借出");
        if (tool.path("calibrationValidUntil").asText().compareTo(DemoDomain.today(clock)) < 0)
          throw fail(409, "CALIBRATION_EXPIRED", "工具校准已过期");
        Instant due = instant(in, "dueAt");
        require(
            due.isAfter(clock.instant())
                && due.isBefore(clock.instant().plus(Duration.ofDays(365))),
            "借用截止时间须在未来一年内");
        ObjectNode loan =
            add(
                state,
                "toolLoans",
                o(
                    "toolId",
                    tool.path("id"),
                    "borrowerId",
                    borrower,
                    "taskId",
                    task,
                    "dueAt",
                    due.toString(),
                    "status",
                    "loaned"),
                actor,
                clock);
        tool.put("status", "loaned");
        tool.put("version", tool.path("version").asInt() + 1);
        return new FeatureResult(
            201,
            o(
                "loanId",
                loan.path("id"),
                "version",
                1,
                "status",
                "loaned",
                "calibrationValid",
                true));
      }
      case "toolReturn" -> {
        fields(in, "version", "note");
        ObjectNode loan = ref(state, "toolLoans", id);
        require(actor.equals("admin") || actor.equals(loan.path("borrowerId").asText()), "无权归还此工具");
        version(loan, in);
        if (!loan.path("status").asText().equals("loaned"))
          throw fail(409, "ALREADY_RETURNED", "工具已归还");
        loan.put("status", "returned");
        loan.put("returnedAt", clock.instant().toString());
        loan.put("version", loan.path("version").asInt() + 1);
        loan.put("note", optional(in, "note", 500));
        ObjectNode tool = ref(state, "tools", loan.path("toolId").asText());
        tool.put("status", "available");
        tool.put("version", tool.path("version").asInt() + 1);
        audit(state, actor, "toolLoans", id, "returned", clock);
        return FeatureResult.ok(loan);
      }
      case "COLLAB" -> {
        fields(in, "orderId", "participantIds", "mode");
        String order = text(in, "orderId", 80);
        entityAccess(state, "order", order, actor);
        String mode = choice(in, "mode", "text", "video");
        if (mode.equals("video"))
          return FeatureDomain.unavailable(state, "integrationJobs", "video", in, actor, clock);
        Set<String> participants = new LinkedHashSet<>();
        participants.add(actor);
        for (JsonNode participant : array(in, "participantIds", 1, 20)) {
          DemoDomain.actor(participant.asText());
          if (!actor.equals("admin")) entityAccess(state, "order", order, participant.asText());
          participants.add(participant.asText());
        }
        ObjectNode room =
            add(
                state,
                "rooms",
                o(
                    "orderId",
                    order,
                    "participantIds",
                    participants,
                    "mode",
                    "text",
                    "expiresAt",
                    clock.instant().plusSeconds(7200).toString()),
                actor,
                clock);
        return new FeatureResult(
            201,
            o(
                "roomId",
                room.path("id"),
                "joinUrl",
                "api/collaboration/rooms/" + room.path("id").asText() + "/messages",
                "expiresAt",
                room.path("expiresAt"),
                "mode",
                "text"));
      }
      case "roomMessage", "roomMessages" -> {
        ObjectNode room = ref(state, "rooms", id);
        roomAccess(room, actor, clock);
        if (code.equals("roomMessages")) {
          ArrayNode messages = empty();
          for (JsonNode row : rows(state, "roomMessages"))
            if (row.path("roomId").asText().equals(id)) messages.add(row);
          return FeatureResult.ok(messages);
        }
        fields(in, "message");
        ObjectNode message =
            add(
                state,
                "roomMessages",
                o("roomId", id, "message", text(in, "message", 2000)),
                actor,
                clock);
        return new FeatureResult(201, message);
      }
      case "AR3D" -> {
        ObjectNode model = ref(state, "models", id);
        String device = query.getOrDefault("deviceId", model.path("deviceId").asText());
        require(device.equals(model.path("deviceId").asText()), "模型与设备不匹配");
        entityAccess(state, "device", device, actor);
        FeatureBusiness.uploaded(state, model.path("fileId").asText(), actor);
        return FeatureResult.ok(
            o(
                "modelUrl",
                "api/files/" + model.path("fileId").asText() + "/content",
                "version",
                model.path("version"),
                "parts",
                model.path("parts"),
                "instructions",
                model.path("instructions"),
                "renderMode",
                "gltf_asset_only",
                "variant",
                query.getOrDefault("variant", "standard")));
      }
      case "TRAINING" -> {
        fields(in, "courseId", "employeeId", "certificationId");
        String employee = text(in, "employeeId", 80);
        DemoDomain.actor(employee);
        require(actor.equals("admin") || actor.equals(employee), "只能报名本人课程");
        String course = text(in, "courseId", 80);
        ref(state, "courses", course);
        for (JsonNode row : rows(state, "enrollments"))
          if (row.path("courseId").asText().equals(course)
              && row.path("employeeId").asText().equals(employee))
            throw fail(409, "ALREADY_ENROLLED", "此账号已报名课程");
        ObjectNode enrollment =
            add(
                state,
                "enrollments",
                o(
                    "courseId",
                    course,
                    "employeeId",
                    employee,
                    "certificationId",
                    optional(in, "certificationId", 80),
                    "progress",
                    0,
                    "qualificationStatus",
                    "not_qualified"),
                actor,
                clock);
        return new FeatureResult(
            201,
            o(
                "enrollmentId",
                enrollment.path("id"),
                "version",
                1,
                "progress",
                0,
                "qualificationStatus",
                "not_qualified"));
      }
      case "trainingComplete" -> {
        fields(in, "version", "answers");
        ObjectNode enrollment = ref(state, "enrollments", id);
        require(
            actor.equals("admin") || actor.equals(enrollment.path("employeeId").asText()),
            "无权提交他人考试");
        version(enrollment, in);
        require(enrollment.path("progress").asInt() == 0, "考试已提交");
        require(in.path("answers").isObject(), "answers须为对象");
        ObjectNode course = ref(state, "courses", enrollment.path("courseId").asText());
        int correct = 0;
        Set<String> expected = new HashSet<>();
        for (JsonNode q : course.path("questions")) {
          expected.add(q.path("id").asText());
          if (q.path("answer")
              .asText()
              .equals(in.path("answers").path(q.path("id").asText()).asText())) correct++;
        }
        require(in.path("answers").size() == expected.size(), "须提交所有题目");
        int score = (int) (100.0 * correct / expected.size());
        boolean passed = score >= course.path("passingScore").asInt();
        enrollment.put("score", score);
        enrollment.put("progress", 100);
        enrollment.put("qualificationStatus", passed ? "qualified" : "failed");
        enrollment.put("version", enrollment.path("version").asInt() + 1);
        if (passed) {
          ObjectNode certificate =
              add(
                  state,
                  "certificates",
                  o(
                      "employeeId",
                      enrollment.path("employeeId"),
                      "courseId",
                      course.path("id"),
                      "status",
                      "valid",
                      "validUntil",
                      LocalDate.parse(DemoDomain.today(clock))
                          .plusDays(course.path("validDays").asInt())
                          .toString(),
                      "kind",
                      "local_training_record"),
                  actor,
                  clock);
          enrollment.put("issuedCertificateId", certificate.path("id").asText());
        }
        audit(state, actor, "enrollments", id, "exam-completed", clock);
        return FeatureResult.ok(enrollment);
      }
      case "AI" -> {
        fields(in, "kind", "deviceId", "datasetVersion", "modelVersion", "inputs");
        String device = text(in, "deviceId", 80);
        entityAccess(state, "device", device, actor);
        String kind = choice(in, "kind", "threshold_evaluation", "prediction", "twin", "voice");
        if (!kind.equals("threshold_evaluation"))
          return FeatureDomain.unavailable(state, "intelligenceJobs", kind, in, actor, clock);
        require(
            in.path("inputs").path("metrics").isObject()
                && in.path("inputs").path("thresholds").isObject(),
            "规则评估需要 metrics 与 thresholds");
        ArrayNode evidence = empty();
        ObjectNode result = o();
        for (var iterator = in.path("inputs").path("metrics").fields(); iterator.hasNext(); ) {
          var item = iterator.next();
          require(
              item.getValue().isNumber()
                  && Double.isFinite(item.getValue().asDouble())
                  && in.path("inputs").path("thresholds").path(item.getKey()).isNumber(),
              "指标及阈值须为数值");
          double limit = in.path("inputs").path("thresholds").path(item.getKey()).asDouble();
          result.put(item.getKey(), item.getValue().asDouble() > limit);
          evidence.add(
              o(
                  "metric",
                  item.getKey(),
                  "value",
                  item.getValue(),
                  "threshold",
                  limit,
                  "source",
                  "client_reported_inputs"));
        }
        ObjectNode job =
            add(
                state,
                "intelligenceJobs",
                o(
                    "deviceId",
                    device,
                    "kind",
                    kind,
                    "status",
                    "completed",
                    "result",
                    result,
                    "evidence",
                    evidence),
                actor,
                clock);
        return FeatureResult.ok(
            o(
                "jobId",
                job.path("id"),
                "result",
                result,
                "evidence",
                evidence,
                "uncertainty",
                null,
                "method",
                "deterministic_threshold_rule_not_prediction"));
      }
      case "LOWCODE" -> {
        admin(actor);
        fields(in, "version", "forms", "workflows", "reports", "locale");
        integer(in, "version", 1, Integer.MAX_VALUE);
        FeatureDomain.validateForms(in.path("forms"));
        for (JsonNode workflow : array(in, "workflows", 0, 30))
          ref(state, "workflows", workflow.asText());
        array(in, "reports", 0, 30);
        choice(in, "locale", "zh-CN", "en-US");
        ObjectNode customization =
            add(
                state,
                "customizations",
                o(
                    "configuration",
                    in,
                    "publishedVersion",
                    in.path("version"),
                    "status",
                    "published_local_metadata"),
                actor,
                clock);
        features(state).put("activeCustomizationId", customization.path("id").asText());
        return new FeatureResult(
            201,
            o(
                "customizationId",
                customization.path("id"),
                "validation",
                o("valid", true, "executionMode", "metadata_only"),
                "publishedVersion",
                in.path("version")));
      }
      case "SECURITY" -> {
        admin(actor);
        fields(in, "from", "to", "entityType", "reason");
        Instant from = instant(in, "from"), to = instant(in, "to");
        require(!to.isBefore(from), "结束时间早于开始时间");
        String type = text(in, "entityType", 80);
        text(in, "reason", 500);
        ArrayNode events = empty();
        for (JsonNode event : state.path("audit")) {
          Instant at = Instant.parse(event.path("at").asText());
          if (!at.isBefore(from)
              && !at.isAfter(to)
              && (type.equals("all") || type.equals(event.path("entityType").asText())))
            events.add(event);
        }
        byte[] bytes =
            DemoDomain.JSON.writeValueAsBytes(
                o(
                    "events",
                    events,
                    "from",
                    from.toString(),
                    "to",
                    to.toString(),
                    "reason",
                    in.get("reason"),
                    "exportedAt",
                    clock.instant().toString()));
        String digest = vault.sign(bytes);
        ObjectNode file =
            vault.generated(state, "audit.json", "application/json", bytes, actor, clock);
        ObjectNode job =
            add(
                state,
                "auditExports",
                o(
                    "fileId",
                    file.path("id"),
                    "signedDigest",
                    digest,
                    "status",
                    "completed",
                    "eventCount",
                    events.size()),
                actor,
                clock);
        return FeatureResult.ok(
            o(
                "jobId",
                job.path("id"),
                "signedDigest",
                digest,
                "archiveUrl",
                "api/files/" + file.path("id").asText() + "/content",
                "algorithm",
                "local HMAC-SHA256",
                "limitation",
                "证明导出内容未变；不证明导出前历史未被本机管理员篡改"));
      }
      case "PUSH" -> {
        admin(actor);
        fields(in, "eventId", "recipientIds", "channels", "templateId", "variables");
        String event = text(in, "eventId", 100);
        for (JsonNode prior : rows(state, "deliveries"))
          if (prior.path("eventId").asText().equals(event)) {
            require(prior.path("request").equals(in), "相同 eventId 对应不同内容");
            return new FeatureResult(prior.path("httpStatus").asInt(), prior.path("result"));
          }
        ArrayNode recipients = array(in, "recipientIds", 1, 20);
        Set<String> unique = new HashSet<>();
        for (JsonNode recipient : recipients) {
          DemoDomain.actor(recipient.asText());
          require(unique.add(recipient.asText()), "收件人重复");
        }
        text(in, "templateId", 80);
        require(in.path("variables").isObject(), "variables须为对象");
        String title = optional(in.path("variables"), "title", 500);
        if (title.isEmpty()) title = "事件通知：" + event;
        ArrayNode results = empty();
        boolean local = false, blocked = false;
        Set<String> channels = new HashSet<>();
        for (JsonNode channel : array(in, "channels", 1, 5)) {
          String name = channel.asText();
          require(channels.add(name), "渠道重复");
          if (name.equals("in_app")) {
            local = true;
            for (String recipient : unique)
              message(state, actor, recipient, title, "notification", event, clock);
            results.add(o("channel", name, "status", "delivered_locally"));
          } else {
            require(Set.of("wechat", "sms", "email").contains(name), "不支持的通知渠道");
            blocked = true;
            results.add(o("channel", name, "status", "not_configured", "delivered", false));
          }
        }
        ObjectNode delivery =
            add(
                state,
                "deliveries",
                o(
                    "eventId",
                    event,
                    "request",
                    in,
                    "status",
                    blocked ? local ? "partial" : "blocked_configuration" : "completed"),
                actor,
                clock);
        int status = blocked ? local ? 207 : 503 : 200;
        JsonNode result =
            o(
                "deliveryId",
                delivery.path("id"),
                "channelResults",
                results,
                "retryAt",
                null,
                "status",
                delivery.path("status"));
        delivery.put("httpStatus", status);
        delivery.set("result", result);
        return new FeatureResult(status, result);
      }
      default -> {
        return null;
      }
    }
  }

  private static boolean hasItem(ObjectNode task, String id) {
    for (JsonNode item : task.path("checklist"))
      if (item.path("id").asText().equals(id)) return true;
    return false;
  }

  private static void roomAccess(ObjectNode room, String actor, Clock clock) {
    boolean participant = false;
    for (JsonNode p : room.path("participantIds")) if (p.asText().equals(actor)) participant = true;
    if (!participant) throw fail(403, "FORBIDDEN", "不是房间参与者");
    if (!Instant.parse(room.path("expiresAt").asText()).isAfter(clock.instant()))
      throw fail(410, "ROOM_EXPIRED", "协作房间已过期");
  }

  private static double distance(double a, double b, double c, double d) {
    double lat = Math.toRadians(c - a), lon = Math.toRadians(d - b);
    double h =
        Math.sin(lat / 2) * Math.sin(lat / 2)
            + Math.cos(Math.toRadians(a))
                * Math.cos(Math.toRadians(c))
                * Math.sin(lon / 2)
                * Math.sin(lon / 2);
    return 6371000 * 2 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h));
  }

  private static JsonNode sync(ObjectNode state, JsonNode in, String actor, Clock clock) {
    fields(in, "clientId", "baseVersion", "operations");
    String client = text(in, "clientId", 80);
    int base = integer(in, "baseVersion", 1, Integer.MAX_VALUE);
    ArrayNode acknowledged = empty(), conflicts = empty();
    for (JsonNode operation : array(in, "operations", 1, 100)) {
      fields(operation, "operationId", "entityId", "payload", "action");
      String operationId = text(operation, "operationId", 100);
      JsonNode prior = null;
      for (JsonNode row : rows(state, "syncOperations"))
        if (row.path("clientId").asText().equals(client)
            && row.path("operationId").asText().equals(operationId)) prior = row;
      if (prior != null) {
        require(
            prior.path("createdBy").asText().equals(actor)
                && prior.path("operation").equals(operation),
            "重复操作内容或账号不一致");
        acknowledged.add(prior.path("result"));
        continue;
      }
      String action =
          choice(
              operation, "action", "createOrder", "completeTask", "completeOrder", "readMessage");
      if (base != features(state).path("serverVersion").asInt() && !action.equals("createOrder")) {
        conflicts.add(o("operationId", operationId, "code", "BASE_VERSION_CONFLICT"));
        continue;
      }
      ObjectNode next = state.deepCopy();
      try {
        JsonNode result =
            DemoDomain.mutate(
                next,
                action,
                optional(operation, "entityId", 80),
                operation.path("payload"),
                actor,
                clock);
        state.removeAll();
        state.setAll(next);
        ObjectNode receipt =
            add(
                state,
                "syncOperations",
                o(
                    "clientId",
                    client,
                    "operationId",
                    operationId,
                    "operation",
                    operation,
                    "result",
                    o("operationId", operationId, "entity", result)),
                actor,
                clock);
        acknowledged.add(receipt.path("result"));
      } catch (DemoException error) {
        conflicts.add(
            o("operationId", operationId, "code", error.code, "message", error.getMessage()));
      }
    }
    return o(
        "acknowledged",
        acknowledged,
        "conflicts",
        conflicts,
        "serverVersion",
        features(state).path("serverVersion").asInt() + 1);
  }
}
