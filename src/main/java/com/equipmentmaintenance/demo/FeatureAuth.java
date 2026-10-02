package com.equipmentmaintenance.demo;

import static com.equipmentmaintenance.demo.FeatureSupport.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Local password/session provider. Enterprise SSO and legacy demo actor headers remain separate.
 */
final class FeatureAuth {
  private static final SecureRandom RANDOM = new SecureRandom();

  private static String random() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  static String digest(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String password(String secret, String salt) {
    PBEKeySpec spec =
        new PBEKeySpec(secret.toCharArray(), Base64.getDecoder().decode(salt), 120000, 256);
    try {
      return Base64.getEncoder()
          .encodeToString(
              SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                  .generateSecret(spec)
                  .getEncoded());
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    } finally {
      spec.clearPassword();
    }
  }

  static void initialize(ObjectNode state, Path dataFile, Clock clock) throws IOException {
    if (rows(state, "accounts").size() > 0) return;
    ObjectNode credentials = o("warning", "仅首次初始化生成；保存于应用公开目录之外。请妥善保管并及时更换密码。");
    for (JsonNode actor : DemoDomain.actors()) {
      String id = actor.path("id").asText(),
          secret = System.getProperty("equipment.demo.seedPassword", random());
      byte[] saltBytes = new byte[16];
      RANDOM.nextBytes(saltBytes);
      String salt = Base64.getEncoder().encodeToString(saltBytes);
      rows(state, "accounts")
          .add(
              o(
                  "id",
                  id,
                  "salt",
                  salt,
                  "passwordHash",
                  password(secret, salt),
                  "active",
                  true,
                  "version",
                  1,
                  "failures",
                  0));
      rows(state, "roles")
          .add(
              o(
                  "id",
                  id,
                  "name",
                  actor.path("name"),
                  "version",
                  1,
                  "operations",
                  "admin".equals(id)
                      ? List.of("*")
                      : List.of(
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
                  "menus",
                  List.of(),
                  "fields",
                  List.of(),
                  "dataScope",
                  o("departmentIds", List.of(actor.path("department").asText()))));
      credentials.set(id, o("account", id, "password", secret));
    }
    Path target = dataFile.resolveSibling(dataFile.getFileName() + ".credentials.json");
    Files.writeString(
        target,
        DemoDomain.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(credentials),
        StandardOpenOption.CREATE_NEW);
  }

  static JsonNode login(ObjectNode state, JsonNode input, Clock clock) {
    fields(input, "account", "credential", "provider");
    choice(input, "provider", "password");
    String id = text(input, "account", 80), secret = text(input, "credential", 200);
    ObjectNode account = null;
    for (JsonNode item : rows(state, "accounts"))
      if (id.equals(item.path("id").asText())) account = (ObjectNode) item;
    if (account == null
        || !account.path("active").asBoolean()
        || !MessageDigest.isEqual(
            account.path("passwordHash").asText().getBytes(java.nio.charset.StandardCharsets.UTF_8),
            password(secret, account.path("salt").asText())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
      throw fail(401, "INVALID_CREDENTIALS", "账号或凭据无效");
    }
    String token = random(), expires = clock.instant().plus(Duration.ofHours(8)).toString();
    for (int i = rows(state, "sessions").size() - 1; i >= 0; i--)
      if (!Instant.parse(rows(state, "sessions").get(i).path("expiresAt").asText())
          .isAfter(clock.instant())) rows(state, "sessions").remove(i);
    int active = 0;
    for (JsonNode session : rows(state, "sessions"))
      if (id.equals(session.path("actorId").asText())) active++;
    if (active >= 20) throw fail(429, "SESSION_LIMIT", "此账号已有20个会话，请先注销");
    rows(state, "sessions")
        .add(
            o(
                "id",
                digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "actorId",
                id,
                "expiresAt",
                expires));
    audit(state, id, "auth", id, "login", clock);
    return o("token", token, "expiresAt", expires, "user", DemoDomain.actor(id));
  }

  static String authenticate(ObjectNode state, String token, Clock clock) {
    if (token == null || token.isBlank())
      throw fail(401, "TOKEN_REQUIRED", "新功能接口需要 Bearer 令牌，请先登录");
    String hash = digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    for (JsonNode session : rows(state, "sessions"))
      if (hash.equals(session.path("id").asText())) {
        String actor = session.path("actorId").asText();
        if (!Instant.parse(session.path("expiresAt").asText()).isAfter(clock.instant())
            || !ref(state, "accounts", actor).path("active").asBoolean()) break;
        return actor;
      }
    throw fail(401, "INVALID_SESSION", "会话已过期或账号已停用");
  }

  static void authorize(ObjectNode state, String actor, String capability) {
    ObjectNode role = ref(state, "roles", actor);
    for (JsonNode permission : role.path("operations"))
      if ("*".equals(permission.asText()) || capability.equals(permission.asText())) return;
    throw fail(403, "FORBIDDEN", "角色未获授权：" + capability);
  }

  static JsonNode changeAccount(
      ObjectNode state, String id, JsonNode input, String actor, Clock clock) {
    admin(actor);
    fields(input, "version", "active");
    ObjectNode account = ref(state, "accounts", id);
    version(account, input);
    require(input.path("active").isBoolean(), "active 须为布尔值");
    require(!"admin".equals(id) || input.path("active").asBoolean(), "不能停用本地管理员");
    account.put("active", input.path("active").asBoolean());
    account.put("version", account.path("version").asInt() + 1);
    audit(state, actor, "accounts", id, "active-changed", clock);
    return o("id", id, "active", account.path("active"), "version", account.path("version"));
  }

  static JsonNode changePassword(ObjectNode state, JsonNode input, String actor, Clock clock) {
    fields(input, "currentPassword", "newPassword");
    ObjectNode account = ref(state, "accounts", actor);
    String current = text(input, "currentPassword", 200), next = text(input, "newPassword", 200);
    require(next.length() >= 12, "新密码至少12字符");
    if (!MessageDigest.isEqual(
        password(current, account.path("salt").asText())
            .getBytes(java.nio.charset.StandardCharsets.UTF_8),
        account.path("passwordHash").asText().getBytes(java.nio.charset.StandardCharsets.UTF_8)))
      throw fail(401, "INVALID_CREDENTIALS", "当前密码错误");
    account.put("passwordHash", password(next, account.path("salt").asText()));
    account.put("version", account.path("version").asInt() + 1);
    revoke(state, actor);
    audit(state, actor, "auth", actor, "password-changed", clock);
    return o("changed", true, "sessionsRevoked", true);
  }

  static void revoke(ObjectNode state, String actor) {
    for (int i = rows(state, "sessions").size() - 1; i >= 0; i--)
      if (rows(state, "sessions").get(i).path("actorId").asText().equals(actor))
        rows(state, "sessions").remove(i);
  }
}
