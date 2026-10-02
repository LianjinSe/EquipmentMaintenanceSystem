package com.equipmentmaintenance.demo;

import static com.equipmentmaintenance.demo.FeatureSupport.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.*;
import java.time.Clock;
import java.util.*;
import java.util.zip.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.imageio.ImageIO;

/** Files live outside webapps. All downloads pass through the authenticated servlet. */
final class FileVault {
  static final int LIMIT = 10 * 1024 * 1024;
  final Path root;
  private final byte[] key;

  FileVault(Path dataFile) throws IOException {
    root = dataFile.resolveSibling(dataFile.getFileName() + ".files");
    Files.createDirectories(root.resolve("blobs"));
    Files.createDirectories(root.resolve("backups"));
    Path keyPath = root.resolve("local-signing.key");
    if (!Files.exists(keyPath)) {
      byte[] bytes = new byte[32];
      new SecureRandom().nextBytes(bytes);
      Files.write(keyPath, bytes, StandardOpenOption.CREATE_NEW);
    }
    key = Files.readAllBytes(keyPath);
    if (key.length != 32) throw new IOException("Invalid local signing key");
  }

  private Path blob(String id) {
    require(id != null && id.matches("[0-9a-fA-F-]{36}"), "文件 ID 无效");
    return root.resolve("blobs").resolve(id);
  }

  void write(String id, byte[] bytes) throws IOException {
    Path target = blob(id);
    if (Files.exists(target)) {
      if (!Arrays.equals(Files.readAllBytes(target), bytes))
        throw fail(409, "IMMUTABLE_FILE", "同一文件不能覆盖为不同内容");
      return;
    }
    Path temporary = Files.createTempFile(root.resolve("blobs"), "upload-", ".tmp");
    try {
      Files.write(temporary, bytes);
      Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(temporary, target);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  byte[] read(String id) throws IOException {
    return Files.readAllBytes(blob(id));
  }

  static void filename(String name) {
    require(
        name.length() <= 100
            && !name.matches(".*[\\\\/\\x00-\\x1f].*")
            && !name.equals(".")
            && !name.equals(".."),
        "文件名不能包含路径或控制字符");
  }

  static void content(String type, byte[] bytes) throws IOException {
    require(bytes.length > 0 && bytes.length <= LIMIT, "文件大小须为 1–10 MiB");
    switch (type) {
      case "image/png", "image/jpeg" -> {
        boolean signature =
            type.equals("image/png")
                ? bytes.length > 8
                    && Arrays.equals(
                        Arrays.copyOf(bytes, 8),
                        new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10})
                : bytes.length > 3
                    && (bytes[0] & 255) == 255
                    && (bytes[1] & 255) == 216
                    && (bytes[2] & 255) == 255;
        require(signature, "图片类型与内容不符");
        try (var image = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
          var readers = ImageIO.getImageReaders(image);
          require(readers.hasNext(), "图片不能解码");
          var reader = readers.next();
          try {
            reader.setInput(image, true, true);
            require(
                reader.getWidth(0) <= 4096 && reader.getHeight(0) <= 4096,
                "图片边长最多4096像素，防止小文件解压为超大图片");
            require(reader.read(0) != null, "图片不能解码");
          } finally {
            reader.dispose();
          }
        }
      }
      case "application/json", "model/gltf+json" -> {
        JsonNode value;
        try {
          value = DemoDomain.JSON.readTree(bytes);
        } catch (IOException e) {
          throw fail(400, "INVALID_FILE", "文件不是有效 JSON");
        }
        require(value != null && value.isObject(), "JSON 文件须为对象");
        if (type.equals("model/gltf+json")) {
          require(value.path("asset").path("version").asText().equals("2.0"), "只接受 glTF 2.0");
          for (JsonNode buffer : value.path("buffers"))
            require(
                !buffer.has("uri") || buffer.path("uri").asText().startsWith("data:"),
                "glTF 不允许外部资源 URI");
          for (JsonNode image : value.path("images"))
            require(
                !image.has("uri") || image.path("uri").asText().startsWith("data:"),
                "glTF 不允许外部图片 URI");
        }
      }
      case "text/plain", "text/csv" -> {
        try {
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .decode(java.nio.ByteBuffer.wrap(bytes));
        } catch (CharacterCodingException e) {
          throw fail(400, "INVALID_FILE", "文本须为 UTF-8");
        }
      }
      default ->
          throw fail(415, "UNSUPPORTED_FILE_TYPE", "支持 PNG/JPEG、UTF-8 文本、CSV、JSON 和内嵌 glTF 2.0");
    }
  }

  ObjectNode generated(
      ObjectNode state, String filename, String type, byte[] bytes, String actor, Clock clock)
      throws IOException {
    ObjectNode file =
        add(
            state,
            "files",
            o(
                "filename",
                filename,
                "contentType",
                type,
                "size",
                bytes.length,
                "status",
                "uploaded",
                "sha256",
                FeatureAuth.digest(bytes),
                "entityType",
                "system",
                "entityId",
                ""),
            actor,
            clock);
    write(file.path("id").asText(), bytes);
    return file;
  }

  String sign(byte[] bytes) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(bytes));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  ObjectNode backup(ObjectNode state, String actor, Clock clock) throws IOException {
    String id = UUID.randomUUID().toString();
    Map<String, byte[]> entries = new TreeMap<>();
    entries.put("state.json", DemoDomain.JSON.writeValueAsBytes(state));
    for (JsonNode file : rows(state, "files"))
      if (file.path("status").asText().equals("uploaded"))
        entries.put("blobs/" + file.path("id").asText(), read(file.path("id").asText()));
    ObjectNode hashes = o();
    entries.forEach((name, bytes) -> hashes.put(name, FeatureAuth.digest(bytes)));
    ObjectNode manifest = o("format", 1, "createdAt", clock.instant().toString(), "hashes", hashes);
    byte[] manifestBytes = DemoDomain.JSON.writeValueAsBytes(manifest);
    entries.put("manifest.json", manifestBytes);
    entries.put("manifest.hmac", sign(manifestBytes).getBytes(StandardCharsets.US_ASCII));
    Path target = root.resolve("backups").resolve(id + ".zip");
    try (ZipOutputStream zip =
        new ZipOutputStream(Files.newOutputStream(target, StandardOpenOption.CREATE_NEW))) {
      for (var entry : entries.entrySet()) {
        zip.putNextEntry(new ZipEntry(entry.getKey()));
        zip.write(entry.getValue());
        zip.closeEntry();
      }
    }
    ObjectNode record =
        add(
            state,
            "backups",
            o(
                "restorePointId",
                id,
                "status",
                "completed",
                "manifest",
                manifest,
                "verification",
                o("algorithm", "SHA-256 + local HMAC-SHA256", "entries", hashes.size())),
            actor,
            clock);
    return o(
        "jobId",
        record.path("id"),
        "status",
        "completed",
        "restorePointId",
        id,
        "manifest",
        manifest,
        "verification",
        record.path("verification"));
  }

  ObjectNode restore(String id) throws IOException {
    require(id.matches("[0-9a-fA-F-]{36}"), "restorePointId 无效");
    Map<String, byte[]> entries = new HashMap<>();
    int total = 0;
    Path archive = root.resolve("backups").resolve(id + ".zip");
    if (!Files.exists(archive)) throw fail(404, "NOT_FOUND", "恢复点不存在");
    try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        String name = entry.getName();
        require(
            name.equals("state.json")
                || name.equals("manifest.json")
                || name.equals("manifest.hmac")
                || name.matches("blobs/[0-9a-fA-F-]{36}"),
            "备份中有非法路径");
        byte[] bytes = zip.readNBytes(50 * 1024 * 1024 + 1);
        total += bytes.length;
        require(total <= 50 * 1024 * 1024, "备份解压超过50 MiB");
        require(entries.put(name, bytes) == null, "备份有重复条目");
      }
    }
    require(entries.containsKey("manifest.json") && entries.containsKey("manifest.hmac"), "缺少备份清单");
    require(
        MessageDigest.isEqual(
            sign(entries.get("manifest.json")).getBytes(StandardCharsets.US_ASCII),
            entries.get("manifest.hmac")),
        "备份签名校验失败");
    JsonNode manifest = DemoDomain.JSON.readTree(entries.get("manifest.json"));
    var names = manifest.path("hashes").fieldNames();
    while (names.hasNext()) {
      String name = names.next();
      require(
          entries.containsKey(name)
              && FeatureAuth.digest(entries.get(name))
                  .equals(manifest.path("hashes").path(name).asText()),
          "备份内容校验失败：" + name);
    }
    require(entries.containsKey("state.json"), "备份缺少数据");
    JsonNode parsed = DemoDomain.JSON.readTree(entries.get("state.json"));
    require(parsed.isObject(), "备份数据无效");
    ObjectNode state = (ObjectNode) parsed;
    for (JsonNode file : rows(state, "files"))
      if (file.path("status").asText().equals("uploaded")) {
        byte[] bytes = entries.get("blobs/" + file.path("id").asText());
        require(
            bytes != null && FeatureAuth.digest(bytes).equals(file.path("sha256").asText()),
            "缺失附件或摘要不匹配");
      }
    return state;
  }

  void restoreFiles(String id, ObjectNode state) throws IOException {
    Map<String, byte[]> content = new HashMap<>();
    try (ZipInputStream zip =
        new ZipInputStream(Files.newInputStream(root.resolve("backups").resolve(id + ".zip")))) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null)
        if (entry.getName().startsWith("blobs/"))
          content.put(entry.getName().substring(6), zip.readNBytes(LIMIT + 1));
    }
    for (JsonNode file : rows(state, "files"))
      if (file.path("status").asText().equals("uploaded"))
        write(file.path("id").asText(), content.get(file.path("id").asText()));
  }
}
